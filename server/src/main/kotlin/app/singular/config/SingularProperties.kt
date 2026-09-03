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

    data class Limits(
        val messageMaxLength: Int = 4000,
        val messagesPageSizeMax: Int = 100,
    )
}
