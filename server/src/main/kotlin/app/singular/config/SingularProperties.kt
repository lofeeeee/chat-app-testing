package app.singular.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "singular")
data class SingularProperties(
    /**
     * Snowflake worker id, 0..1023. Must be unique across every running instance.
     *
     * Two nodes sharing an id will mint colliding snowflakes. That surfaces as duplicate-key
     * errors on insert if you are lucky, and as silently overwritten rows if you are not.
     */
    val nodeId: Long = 1,
    val auth: Auth = Auth(),
    val crypto: Crypto = Crypto(),
    val limits: Limits = Limits(),
    val rateLimit: RateLimit = RateLimit(),
    val valkey: Valkey = Valkey(),
    val storage: Storage = Storage(),
    val media: Media = Media(),
) {
    data class Storage(
        val endpoint: String = "http://localhost:9100",
        val region: String = "us-east-1",
        val bucket: String = "singular",
        val accessKey: String = "singular",
        val secretKey: String = "singular-dev-only",
        /** MinIO requires path-style addressing; real S3 prefers virtual-host style. */
        val pathStyle: Boolean = true,
        val uploadUrlTtl: Duration = Duration.ofMinutes(10),
        val downloadUrlTtl: Duration = Duration.ofHours(1),
    )

    data class Media(
        val maxUploadBytes: Long = 100L * 1024 * 1024,
        val maxImageBytes: Long = 25L * 1024 * 1024,
        val thumbnailMaxEdge: Int = 320,
        val storyTtl: Duration = Duration.ofHours(24),
    )
    data class Auth(
        val tokenSecret: String = "dev-only-insecure-token-secret-change-me-now",
        val accessTokenTtl: Duration = Duration.ofMinutes(15),
        val refreshTokenTtl: Duration = Duration.ofDays(30),
    )

    data class Crypto(
        val pepper: String = "dev-only-insecure-pepper-change-me-now",
    )

    data class Valkey(
        /**
         * Redis-compatible URI of the Valkey server (docker-compose publishes 6380 on the host).
         * Everything stored there is volatile by design; losing it costs online/offline
         * flicker and a few missed typing indicators, never data.
         */
        val uri: String = "redis://localhost:6380",
    )

    data class Limits(
        val messageMaxLength: Int = 4000,
        val messagesPageSizeMax: Int = 100,
    )

    /**
     * Rate limiting, one policy per sensitive scope.
     *
     * The numbers below are per identity (IP for anonymous endpoints, user id once signed in),
     * and every one of them is a trade between abuse resistance and legitimate frustration:
     *
     *   * login/register/refresh — credential stuffing and account enumeration. Tight, because
     *     a real person types a password a handful of times, not dozens.
     *   * QR create/rotate — the anonymous edge. Unbounded minting of login requests was the
     *     largest open hole before this existed.
     *   * send-message — flood control, not abuse control. Generous, because the failure mode
     *     of limiting a chatty human is support tickets.
     *
     * Scope names are the string used at the `RateLimiter.acquireOrThrow` call sites. An
     * unknown scope fails closed at policy lookup — a typo'd scope name throwing at startup
     * beats a rate limit that silently doesn't apply.
     */
    data class RateLimit(
        val login: Policy = Policy(capacity = 10, refillPerMinute = 5.0),
        val register: Policy = Policy(capacity = 3, refillPerMinute = 1.0),
        val tokenRefresh: Policy = Policy(capacity = 20, refillPerMinute = 10.0),
        val qrCreate: Policy = Policy(capacity = 6, refillPerMinute = 3.0),
        val qrRotate: Policy = Policy(capacity = 30, refillPerMinute = 15.0),
        val qrClaim: Policy = Policy(capacity = 20, refillPerMinute = 10.0),
        val sendMessage: Policy = Policy(capacity = 30, refillPerMinute = 120.0),
    ) {
        data class Policy(val capacity: Int, val refillPerMinute: Double)

        fun policyFor(scope: String): Policy = when (scope) {
            "login" -> login
            "register" -> register
            "token-refresh" -> tokenRefresh
            "qr-create" -> qrCreate
            "qr-rotate" -> qrRotate
            "qr-claim" -> qrClaim
            "send-message" -> sendMessage
            else -> throw IllegalStateException(
                "No rate-limit policy for scope '$scope'. Add it to SingularProperties.RateLimit " +
                    "or fix the typo at the call site — failing closed here is deliberate."
            )
        }
    }
}
