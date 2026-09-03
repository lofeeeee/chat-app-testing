package app.singular.audit

import app.singular.core.Snowflake
import app.singular.domain.AuditAction
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Timestamp
import java.time.Instant

/**
 * The action audit trail: logins, logouts, password changes, profile edits, moderation.
 *
 * This is low-volume and every event is kept — unlike per-message forensics, where the cost is
 * ~300 bytes per message and the answer is to store a `session_id` foreign key instead.
 * See `connection_sessions` in the baseline migration.
 *
 * Writes are best-effort: an audit failure must never take down the action being audited. It's
 * logged loudly instead, because a silently broken audit trail is worse than none.
 */
@Component
class AuditLog(
    private val jdbc: JdbcClient,
    private val snowflake: Snowflake,
    private val objectMapper: ObjectMapper,
) {

    /**
     * Runs in its own transaction, deliberately.
     *
     * Half the events worth auditing happen on paths that then fail: a rejected login records
     * LOGIN_FAILED and throws, a replayed refresh token records TOKEN_REUSE_DETECTED and throws.
     * Joining the caller's transaction means the throw rolls the audit row back too, and the
     * security events you most want a record of are exactly the ones that silently vanish.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun record(
        actorId: Long,
        action: AuditAction,
        sessionId: Long? = null,
        targetType: Short? = null,
        targetId: Long? = null,
        changes: Map<String, Any?>? = null,
        occurredAt: Instant = Instant.now(),
    ) {
        try {
            jdbc.sql(
                """
                INSERT INTO audit_events
                    (id, actor_id, session_id, action, target_type, target_id, changes, occurred_at)
                VALUES (:id, :actor, :session, :action, :targetType, :targetId,
                        CAST(:changes AS jsonb), :at)
                """
            )
                .param("id", snowflake.next())
                .param("actor", actorId)
                .param("session", sessionId)
                .param("action", action.code.toInt())
                .param("targetType", targetType?.toInt())
                .param("targetId", targetId)
                .param("changes", changes?.let(::toJsonb))
                .param("at", Timestamp.from(occurredAt))
                .update()
        } catch (e: Exception) {
            LOG.error(
                "Audit write failed for actor={} action={} — the action itself still succeeded",
                actorId, action, e,
            )
        }
    }

    /**
     * Binds JSON as a plain String and lets `CAST(? AS jsonb)` in the SQL do the conversion.
     *
     * The alternative — org.postgresql.util.PGobject — drags a driver-internal type into
     * application code and forces the JDBC driver onto the compile classpath, which is exactly
     * the coupling `runtimeOnly` exists to prevent.
     */
    private fun toJsonb(map: Map<String, Any?>): String = objectMapper.writeValueAsString(map)

    private companion object {
        val LOG = org.slf4j.LoggerFactory.getLogger(AuditLog::class.java)!!
    }
}
