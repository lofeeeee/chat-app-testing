package app.singular.security

import app.singular.config.SingularProperties
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Stateless, short-lived access tokens.
 *
 * Format: `v1.<base64url(userId:sessionId:expiryEpochSeconds)>.<base64url(HMAC-SHA256)>`
 *
 * This is a deliberately minimal alternative to a JWT library. The tokens are opaque to
 * clients, we are the only issuer and the only verifier, and there is no algorithm field —
 * so the entire class of "alg: none" and algorithm-confusion attacks simply doesn't exist
 * here. It also keeps a dependency out of the build, which matters given the no-remote-
 * dependencies rule.
 *
 * If you later need third parties to verify tokens without your secret, that's the point to
 * switch to asymmetric JWTs — not before.
 *
 * These tokens are NOT revocable before expiry. That is the trade for not hitting the
 * database on every request; the 15-minute TTL is what bounds the damage. Anything that must
 * take effect immediately (ban, forced logout) has to also revoke the refresh session and be
 * enforced at the gateway on the next reconnect.
 */
@Component
class AccessTokens(private val props: SingularProperties) {

    private val key = SecretKeySpec(props.auth.tokenSecret.toByteArray(), HMAC_ALG)
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getUrlDecoder()

    val ttl: Duration get() = props.auth.accessTokenTtl

    init {
        if (props.auth.tokenSecret.startsWith("dev-only-")) {
            LOG.warn(
                "singular.auth.token-secret is the built-in development value. " +
                    "Set SINGULAR_TOKEN_SECRET before this reaches anything but localhost."
            )
        }
    }

    data class Claims(val userId: Long, val sessionId: Long, val expiresAt: Instant)

    fun issue(userId: Long, sessionId: Long, now: Instant = Instant.now()): String {
        val expiry = now.plus(ttl).epochSecond
        val payload = "$userId:$sessionId:$expiry".toByteArray(StandardCharsets.UTF_8)
        val body = encoder.encodeToString(payload)
        return "$VERSION.$body.${encoder.encodeToString(sign(body))}"
    }

    /** Returns null for anything malformed, mis-signed or expired — callers treat all three alike. */
    fun verify(token: String, now: Instant = Instant.now()): Claims? {
        val parts = token.split('.')
        if (parts.size != 3 || parts[0] != VERSION) return null

        val (_, body, signature) = parts

        val expected = sign(body)
        val presented = try {
            decoder.decode(signature)
        } catch (_: IllegalArgumentException) {
            return null
        }
        // Constant-time: a byte-by-byte early exit leaks how much of a forged signature is right.
        if (!MessageDigest.isEqual(expected, presented)) return null

        val fields = try {
            String(decoder.decode(body), StandardCharsets.UTF_8).split(':')
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (fields.size != 3) return null

        val userId = fields[0].toLongOrNull() ?: return null
        val sessionId = fields[1].toLongOrNull() ?: return null
        val expiry = fields[2].toLongOrNull() ?: return null

        if (now.epochSecond >= expiry) return null

        return Claims(userId, sessionId, Instant.ofEpochSecond(expiry))
    }

    private fun sign(body: String): ByteArray =
        Mac.getInstance(HMAC_ALG).apply { init(key) }
            .doFinal(body.toByteArray(StandardCharsets.UTF_8))

    private companion object {
        const val HMAC_ALG = "HmacSHA256"
        const val VERSION = "v1"
        val LOG = org.slf4j.LoggerFactory.getLogger(AccessTokens::class.java)!!
    }
}
