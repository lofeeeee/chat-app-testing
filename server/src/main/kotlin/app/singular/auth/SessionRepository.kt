package app.singular.auth

import app.singular.domain.AuthSession
import app.singular.domain.DeviceSession
import app.singular.domain.SessionOrigin
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class SessionRepository(private val jdbc: JdbcClient) {

    fun create(
        id: Long,
        userId: Long,
        refreshTokenHash: ByteArray,
        familyId: Long,
        deviceId: UUID,
        userAgent: String?,
        ip: String?,
        platform: String?,
        origin: SessionOrigin,
        expiresAt: Instant,
    ) {
        jdbc.sql(
            """
            INSERT INTO auth_sessions
                (id, user_id, refresh_token_hash, family_id, device_id, user_agent, ip,
                 platform, created_via, expires_at)
            VALUES
                (:id, :userId, :hash, :family, :device, :ua, cast(:ip AS inet),
                 :platform, :via, :expires)
            """
        )
            .param("id", id)
            .param("userId", userId)
            .param("hash", refreshTokenHash)
            .param("family", familyId)
            .param("device", deviceId)
            .param("ua", userAgent)
            .param("ip", ip)
            .param("platform", platform)
            .param("via", origin.code.toInt())
            .param("expires", Timestamp.from(expiresAt))
            .update()
    }

    /**
     * The "where you're signed in" list.
     *
     * One row per *family*, not per session. Refresh rotation mints a new row every 15 minutes,
     * so listing raw sessions would show the same laptop dozens of times a day. `DISTINCT ON`
     * picks the live head of each chain; the joined subquery recovers when that chain actually
     * started, which is the date a person recognises as "when I signed in".
     */
    fun listDevices(userId: Long): List<DeviceSession> = jdbc
        .sql(
            """
            SELECT DISTINCT ON (s.family_id)
                   s.family_id, s.id, s.device_id, s.user_agent, host(s.ip) AS ip_text,
                   s.platform, s.created_via, s.last_seen_at, s.expires_at,
                   f.first_seen
            FROM auth_sessions s
            JOIN (
                SELECT family_id, min(created_at) AS first_seen
                FROM auth_sessions WHERE user_id = :u GROUP BY family_id
            ) f ON f.family_id = s.family_id
            WHERE s.user_id = :u AND s.revoked_at IS NULL AND s.expires_at > now()
            ORDER BY s.family_id, s.id DESC
            """
        )
        .param("u", userId)
        .query { rs, _ ->
            DeviceSession(
                familyId = rs.getLong("family_id"),
                liveSessionId = rs.getLong("id"),
                deviceId = rs.getObject("device_id", UUID::class.java),
                userAgent = rs.getString("user_agent"),
                ipAddress = rs.getString("ip_text"),
                platform = rs.getString("platform"),
                origin = SessionOrigin.of(rs.getShort("created_via")),
                firstSeenAt = rs.getTimestamp("first_seen").toInstant(),
                lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
                expiresAt = rs.getTimestamp("expires_at").toInstant(),
            )
        }
        .list()
        .sortedByDescending { it.lastSeenAt }

    /** Ownership is part of the WHERE clause, so a forged family id can't revoke someone else's. */
    fun revokeFamilyForUser(familyId: Long, userId: Long): Int = jdbc
        .sql(
            """
            UPDATE auth_sessions SET revoked_at = now()
            WHERE family_id = :f AND user_id = :u AND revoked_at IS NULL
            """
        )
        .param("f", familyId)
        .param("u", userId)
        .update()

    fun revokeAllExceptFamily(userId: Long, keepFamilyId: Long): Int = jdbc
        .sql(
            """
            UPDATE auth_sessions SET revoked_at = now()
            WHERE user_id = :u AND family_id <> :keep AND revoked_at IS NULL
            """
        )
        .param("u", userId)
        .param("keep", keepFamilyId)
        .update()

    /** Which family a live session belongs to — used to identify "this device" in the list. */
    fun familyOf(sessionId: Long): Long? = jdbc
        .sql("SELECT family_id FROM auth_sessions WHERE id = :id")
        .param("id", sessionId)
        .query(Long::class.java)
        .optional()
        .orElse(null)

    fun findByTokenHash(hash: ByteArray): AuthSession? = jdbc
        .sql(
            """
            SELECT id, user_id, family_id, device_id, expires_at, revoked_at, superseded_by
            FROM auth_sessions WHERE refresh_token_hash = :hash
            """
        )
        .param("hash", hash)
        .query { rs, _ ->
            AuthSession(
                id = rs.getLong("id"),
                userId = rs.getLong("user_id"),
                familyId = rs.getLong("family_id"),
                deviceId = rs.getObject("device_id", UUID::class.java),
                expiresAt = rs.getTimestamp("expires_at").toInstant(),
                revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
                supersededBy = rs.getObject("superseded_by") as Long?,
            )
        }
        .optional()
        .orElse(null)

    fun markSuperseded(id: Long, bySessionId: Long) {
        jdbc.sql(
            """
            UPDATE auth_sessions
            SET superseded_by = :by, last_seen_at = now()
            WHERE id = :id
            """
        )
            .param("by", bySessionId)
            .param("id", id)
            .update()
    }

    fun revoke(id: Long) {
        jdbc.sql("UPDATE auth_sessions SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
            .param("id", id)
            .update()
    }

    /**
     * Revokes an entire rotation chain.
     *
     * Called when an already-rotated refresh token is presented: either the legitimate client
     * replayed an old token, or someone stole it. We can't tell which, and the safe reading is
     * theft — so every session descended from that login goes.
     */
    fun revokeFamily(familyId: Long): Int = jdbc
        .sql("UPDATE auth_sessions SET revoked_at = now() WHERE family_id = :f AND revoked_at IS NULL")
        .param("f", familyId)
        .update()

    fun revokeAllForUser(userId: Long): Int = jdbc
        .sql("UPDATE auth_sessions SET revoked_at = now() WHERE user_id = :u AND revoked_at IS NULL")
        .param("u", userId)
        .update()

    fun touch(id: Long) {
        jdbc.sql("UPDATE auth_sessions SET last_seen_at = now() WHERE id = :id")
            .param("id", id)
            .update()
    }
}
