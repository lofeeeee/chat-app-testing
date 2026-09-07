package app.singular.push

import app.singular.config.SingularProperties
import com.fasterxml.jackson.databind.ObjectMapper
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Instant
import java.util.Base64

/**
 * Apple Push Notification service over HTTP/2, with token-based (.p8) authentication.
 *
 * APNs is the one provider with no alternative: Apple owns the only socket to a sleeping
 * iPhone, which is exactly why it sits behind the same [PushTransport] seam as FCM — the app
 * must run identically with it absent.
 *
 * Auth model: the .p8 key signs a tiny ES256 JWT (key id + team id + expiry) that is sent as
 * the `authorization` header on every request. Apple allows the token to live up to an hour;
 * we mint per request instead of caching, because the JWT is ~50 bytes of signing — far
 * cheaper than the staleness bugs a cached one invites (Apple rejects tokens older than 1h
 * with a 403 that looks identical to a bad key).
 */
class ApnsTransport(
    private val config: SingularProperties.Push.Apns,
    private val mapper: ObjectMapper,
) : PushTransport {

    override val platform = PushPlatform.APNS

    private val host =
        if (config.sandbox) "https://api.sandbox.push.apple.com"
        else "https://api.push.apple.com"

    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).build()

    // Parsed once. A malformed key fails the first send, and the transport is only registered
    // when credentials exist at all (see PushTransportConfig).
    private val privateKey: ECPrivateKey = parseP8(config.p8Key)

    override fun send(token: String, message: PushMessage): Boolean {
        val payload = mapper.writeValueAsString(
            mapOf(
                "aps" to mapOf(
                    "alert" to mapOf(
                        "title" to message.title,
                        "body" to message.body,
                    ),
                    "sound" to "default",
                ),
                "channelId" to (message.channelId?.toString() ?: ""),
                "messageId" to (message.messageId?.toString() ?: ""),
            )
        )

        val request = HttpRequest.newBuilder()
            .uri(URI.create("$host/3/device/$token"))
            .header("authorization", "bearer ${jwt()}")
            .header("apns-topic", config.topic)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build()

        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        return when (response.statusCode()) {
            200 -> true
            // 410 Unregistered — the app was removed or the token rolled. Dead.
            410 -> false
            403 -> {
                // BadToken or invalid credentials. A bad provider JWT would fail for every
                // token, so distinguish by the reason phrase.
                if (response.body().contains("BadToken", ignoreCase = true)) false
                else {
                    LOG.error("APNs rejected our credentials — check APNS_* settings")
                    throw PushRetryable("403 ${response.body().take(120)}")
                }
            }
            429, 500, 503 -> {
                LOG.warn("APNs transient failure {} — will retry", response.statusCode())
                throw PushRetryable("status ${response.statusCode()}")
            }
            else -> {
                // 400 BadCollapseId, 403 otherwise, and friends: the token is suspect.
                LOG.warn("APNs rejected a send: {} {}", response.statusCode(), response.body().take(200))
                false
            }
        }
    }

    /** The tiny ES256 JWT Apple wants. */
    private fun jwt(): String {
        val now = Instant.now().epochSecond
        val header = b64url("""{"alg":"ES256","kid":"${config.keyId}"}""")
        val claims = b64url("""{"iss":"${config.teamId}","iat":$now}""")

        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(privateKey)
        signer.update("$header.$claims".toByteArray())
        val raw = signer.sign()

        // Java's ECDSA produces ASN.1 DER; the JWS signature is raw r||s. Convert.
        val (r, s) = parseDer(raw)
        val sig = ByteArray(64)
        r.copyInto(sig, 0, maxOf(0, r.size - 32))
        s.copyInto(sig, 64, maxOf(0, s.size - 32))

        return "$header.$claims.${b64url(sig)}"
    }

    /** DER SEQUENCE { INTEGER r, INTEGER s } → (r, s), leading-zero-padded to 32 bytes. */
    private fun parseDer(der: ByteArray): Pair<ByteArray, ByteArray> {
        var i = 2   // skip SEQUENCE tag + length
        fun readInt(): ByteArray {
            val len = der[i].toInt() and 0xFF
            val start = i + 2
            val end = start + len
            i = end
            return der.copyOfRange(start, end)
        }
        val r = readInt()
        val s = readInt()
        return r to s
    }

    private fun parseP8(pem: String): ECPrivateKey {
        PEMParser(pem.reader()).use { parser ->
            val obj = parser.readObject() ?: error("Unparseable .p8 PEM")
            val info = when (obj) {
                is PrivateKeyInfo -> obj
                else -> error("Expected a PKCS#8 EC key, got ${obj::class.simpleName}")
            }
            val spec = PKCS8EncodedKeySpec(info.encoded)
            return KeyFactory.getInstance("EC").generatePrivate(spec) as ECPrivateKey
        }
    }

    private fun b64url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun b64url(text: String): String = b64url(text.toByteArray())

    private companion object {
        val LOG = LoggerFactory.getLogger(ApnsTransport::class.java)!!
    }
}
