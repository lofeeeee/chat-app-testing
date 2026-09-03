package app.singular.auth

import app.singular.domain.LoginRequest
import app.singular.domain.LoginRequestStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class LoginRequestRepository(private val jdbc: JdbcClient) {

    fun create(
        id: Long,
        tokenHash: ByteArray,
        pollSecretHash: ByteArray,
        ip: String?,
        userAgent: String?,
        platform: String?,
        deviceId: java.util.UUID,
        tokenExpiresAt: Instant,
        expiresAt: Instant,
    ) {
        jdbc.sql(
            """
            INSERT INTO login_requests
                (id, token_hash, poll_secret_hash, request_ip, request_user_agent,
                 request_platform, request_device_id, token_expires_at, expires_at)
            VALUES (:id, :token, :secret, cast(:ip AS inet), :ua, :platform, :device,
                    :tokenExp, :exp)
            """
        )
            .param("id", id)
            .param("token", tokenHash)
            .param("secret", pollSecretHash)
            .param("ip", ip)
            .param("ua", userAgent)
            .param("platform", platform)
            .param("device", deviceId)
            .param("tokenExp", Timestamp.from(tokenExpiresAt))
            .param("exp", Timestamp.from(expiresAt))
            .update()
    }

    fun findById(id: Long): LoginRequest? = jdbc
        .sql("$SELECT_COLS WHERE id = :id")
        .param("id", id)
        .query(::map)
        .optional()
        .orElse(null)

    /** Also returns the stored poll-secret hash, for the caller to compare in constant time. */
    fun findPollSecretHash(id: Long): ByteArray? = jdbc
        .sql("SELECT poll_secret_hash FROM login_requests WHERE id = :id")
        .param("id", id)
        .query(ByteArray::class.java)
        .optional()
        .orElse(null)

    fun findByTokenHash(tokenHash: ByteArray): LoginRequest? = jdbc
        .sql("$SELECT_COLS WHERE token_hash = :token")
        .param("token", tokenHash)
        .query(::map)
        .optional()
        .orElse(null)

    /**
     * Swaps in a new QR token.
     *
     * Guarded on `status = PENDING`: once a phone has scanned, the token it captured must stay
     * valid until the user approves or denies. Rotating out from under a scan would make the
     * approval screen fail for no visible reason.
     */
    fun rotateToken(id: Long, newTokenHash: ByteArray, tokenExpiresAt: Instant): Boolean =
        jdbc.sql(
            """
            UPDATE login_requests
            SET token_hash = :token, token_expires_at = :exp
            WHERE id = :id AND status = 0 AND expires_at > now()
            """
        )
            .param("token", newTokenHash)
            .param("exp", Timestamp.from(tokenExpiresAt))
            .param("id", id)
            .update() == 1

    /**
     * PENDING -> SCANNED, atomically.
     *
     * The `status = 0` guard is what makes a scan single-use: two phones racing on the same
     * captured QR, exactly one wins.
     */
    fun markScanned(id: Long, userId: Long): Boolean =
        jdbc.sql(
            """
            UPDATE login_requests
            SET status = 1, claimed_by = :user, claimed_at = now()
            WHERE id = :id AND status = 0 AND expires_at > now()
            """
        )
            .param("user", userId)
            .param("id", id)
            .update() == 1

    /** SCANNED -> APPROVED/DENIED, and only by the user who claimed it. */
    fun resolve(id: Long, userId: Long, status: LoginRequestStatus): Boolean =
        jdbc.sql(
            """
            UPDATE login_requests
            SET status = :status
            WHERE id = :id AND status = 1 AND claimed_by = :user AND expires_at > now()
            """
        )
            .param("status", status.code.toInt())
            .param("id", id)
            .param("user", userId)
            .update() == 1

    /** APPROVED -> CONSUMED. Guarded so tokens are delivered exactly once. */
    fun markConsumed(id: Long): Boolean =
        jdbc.sql(
            """
            UPDATE login_requests
            SET status = 5, consumed_at = now()
            WHERE id = :id AND status = 2
            """
        )
            .param("id", id)
            .update() == 1

    fun expireStale(): Int = jdbc
        .sql("UPDATE login_requests SET status = 4 WHERE expires_at <= now() AND status IN (0,1)")
        .update()

    fun deleteResolvedBefore(cutoff: Instant): Int = jdbc
        .sql("DELETE FROM login_requests WHERE status IN (3,4,5) AND expires_at < :cutoff")
        .param("cutoff", Timestamp.from(cutoff))
        .update()

    private companion object {
        const val SELECT_COLS = """
            SELECT id, status, claimed_by, host(request_ip) AS request_ip, request_user_agent,
                   request_platform, request_device_id, created_at, token_expires_at, expires_at
            FROM login_requests
        """

        fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = LoginRequest(
            id = rs.getLong("id"),
            status = LoginRequestStatus.of(rs.getShort("status")),
            claimedBy = rs.getObject("claimed_by") as Long?,
            requestIp = rs.getString("request_ip"),
            requestUserAgent = rs.getString("request_user_agent"),
            requestPlatform = rs.getString("request_platform"),
            requestDeviceId = rs.getObject("request_device_id", java.util.UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            tokenExpiresAt = rs.getTimestamp("token_expires_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
        )
    }
}
