package app.singular.push

import app.singular.config.SingularProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMKeyPair
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.RSAPrivateKey
import java.time.Instant
import java.util.Base64

/**
 * Firebase Cloud Messaging over the HTTP v1 API.
 *
 * Hand-rolled rather than firebase-admin on purpose: the dependency philosophy of this
 * codebase is minimal and vendored, and the v1 protocol is two calls — mint an OAuth2 access
 * token from a service-account JWT, POST a JSON message. The SDK buys Firebase's entire
 * surface for those two calls.
 *
 * Token auth: the service account's private key signs a short-lived JWT (RS256); Google's
 * token endpoint exchanges it for an access token good for an hour. The exchange is cached
 * and refreshed ahead of expiry, because paying a token round trip per notification would
 * double the latency of the final hop for nothing.
 */
class FcmTransport(
    private val config: SingularProperties.Push.Fcm,
    private val mapper: ObjectMapper,
) : PushTransport {

    override val platform = PushPlatform.FCM

    private val http = HttpClient.newHttpClient()

    // Parsed once at construction. A malformed credential set fails the first send loudly
    // (and the transport never gets registered — see PushTransportConfig).
    private val account: ServiceAccount = parseServiceAccount(config.serviceAccountJson)

    @Volatile
    private var cachedToken: CachedToken? = null

    private class CachedToken(val bearer: String, val expiresAt: Instant)

    private class ServiceAccount(val email: String, val keyId: String, val keyPair: KeyPair)

    override fun send(token: String, message: PushMessage): Boolean {
        val auth = bearerToken() ?: run {
            LOG.warn("FCM: could not mint an access token — dropping to retry")
            throw PushRetryable("token exchange failed")
        }

        // Minimal message shape: title, body, and the ids a client needs to deep-link. The
        // drawer rendering is the client's business; this is the delivery hop.
        val payload = mapper.writeValueAsString(
            mapOf(
                "message" to mapOf(
                    "token" to token,
                    "notification" to mapOf(
                        "title" to message.title,
                        "body" to message.body,
                    ),
                    "data" to mapOf(
                        "channelId" to (message.channelId?.toString() ?: ""),
                        "messageId" to (message.messageId?.toString() ?: ""),
                    ),
                ),
            )
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://fcm.googleapis.com/v1/projects/${config.projectId}/messages:send"))
            .header("Authorization", auth)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> true
            // 404 UNREGISTERED, 410 UNINSTALLED: the token is dead, so say so — the caller
            // marks it invalid and it stops being tried on every message.
            404, 410 -> false
            401, 403 -> {
                // Bad credentials can't be retried by this process; treat as a dead token so
                // the outbox gives up rather than spinning.
                LOG.error("FCM rejected our credentials ({}). Check FCM_* settings.", response.statusCode())
                false
            }
            429, 500, 502, 503 -> {
                LOG.warn("FCM transient failure {} — will retry", response.statusCode())
                throw PushRetryable("status ${response.statusCode()}")
            }
            else -> {
                // 400 INVALID_ARGUMENT most often: also a dead-or-wrong token.
                LOG.warn("FCM rejected a send: {} {}", response.statusCode(), response.body().take(200))
                false
            }
        }
    }

    /** Mint (or reuse) the OAuth2 bearer for the v1 API. */
    private fun bearerToken(): String? {
        cachedToken?.let { cached ->
            if (cached.expiresAt.isAfter(Instant.now().plusSeconds(60))) return cached.bearer
        }

        val now = Instant.now().epochSecond
        val header = b64url("""{"alg":"RS256","typ":"JWT","kid":"${account.keyId}"}""")
        val claims = b64url(
            """{"iss":"${account.email}",""" +
                """"scope":"https://www.googleapis.com/auth/firebase.messaging",""" +
                """"aud":"https://oauth2.googleapis.com/token",""" +
                """"exp":${now + TOKEN_TTL_SECONDS},"iat":$now}"""
        )
        val signer = Signature.getInstance("RS256")
        signer.initSign(account.keyPair.private as RSAPrivateKey)
        signer.update("$header.$claims".toByteArray())
        val signature = b64url(signer.sign())
        val assertion = "$header.$claims.$signature"

        val request = HttpRequest.newBuilder()
            .uri(URI.create("https://oauth2.googleapis.com/token"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer&assertion=$assertion"
                )
            )
            .build()

        return runCatching {
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                LOG.warn("FCM token exchange failed: {}", response.statusCode())
                return null
            }
            val tree = mapper.readTree(response.body())
            val value = tree.get("access_token")?.asText()
                ?: return null
            val expiresIn = tree.get("expires_in")?.asLong() ?: TOKEN_TTL_SECONDS
            val bearer = "Bearer $value"
            cachedToken = CachedToken(bearer, Instant.now().plusSeconds(expiresIn))
            bearer
        }.getOrNull()
    }

    private fun parseServiceAccount(json: String): ServiceAccount {
        val tree = mapper.readTree(json)
        val email = tree.get("client_email")?.asText()
            ?: error("Service account JSON has no client_email")
        val keyId = tree.get("private_key_id")?.asText().orEmpty()
        val pem = tree.get("private_key")?.asText()
            ?: error("Service account JSON has no private_key")

        PEMParser(pem.reader()).use { parser ->
            val obj = parser.readObject() ?: error("Unparseable private key PEM")
            val info = when (obj) {
                is PEMKeyPair -> obj.privateKeyInfo
                is PrivateKeyInfo -> obj
                else -> error("Unexpected PEM object: ${obj::class.simpleName}")
            }
            return ServiceAccount(email, keyId, JcaPEMKeyConverter().getKeyPair(info))
        }
    }

    private fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun b64url(text: String): String = b64url(text.toByteArray())

    private companion object {
        val LOG = LoggerFactory.getLogger(FcmTransport::class.java)!!
        const val TOKEN_TTL_SECONDS = 3600L
    }
}

/** A provider failure worth retrying — the outbox reschedules rather than dropping. */
class PushRetryable(message: String) : RuntimeException(message)

/**
 * Builds the transport list from whatever credentials exist.
 *
 * An empty credential set means that provider's transport is simply absent — the app runs
 * exactly as before, with the logging stand-in covering the final hop so the whole path stays
 * exercised. Pasting real keys in (via env vars) is what turns delivery on; there is no code
 * change and no feature flag.
 *
 * Exposed as a single List bean: Spring injects an existing collection bean as-is, so
 * `PushService`'s `List<PushTransport>` gets exactly this list and nothing else.
 */
@Configuration
@EnableConfigurationProperties(SingularProperties::class)
class PushTransportConfig(
    private val props: SingularProperties,
    private val mapper: ObjectMapper,
) {

    @Bean
    fun pushTransports(): List<PushTransport> {
        val real = buildList {
            if (props.push.fcm.configured) {
                add(FcmTransport(props.push.fcm, mapper))
                LOG.info("Push delivery: FCM enabled (project {})", props.push.fcm.projectId)
            }
            if (props.push.apns.configured) {
                add(ApnsTransport(props.push.apns, mapper))
                LOG.info("Push delivery: APNs enabled (topic {})", props.push.apns.topic)
            }
            if (props.push.webpush.configured) {
                add(WebPushTransport(props.push.webpush, mapper))
                LOG.info("Push delivery: Web Push enabled")
            }
        }
        if (real.isNotEmpty()) return real

        LOG.info(
            "Push delivery: no provider credentials configured — using the logging stand-in. " +
                "Set FCM_*/APNS_*/VAPID_* env vars to enable real delivery."
        )
        return listOf(LoggingPushTransport())
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(PushTransportConfig::class.java)!!
    }
}
