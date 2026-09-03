package app.singular.message

import app.singular.auth.LoginRequestRepository
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Housekeeping that has to happen or the schema slowly stops working.
 *
 * Both jobs are safe to run on every node — they're idempotent — but in a multi-node
 * deployment you'd want a lock (Valkey `SET NX`, or `pg_try_advisory_lock`) so they don't all
 * fire at once. Phase 2, alongside the Valkey wiring.
 */
@Component
@EnableScheduling
class MessageReaper(
    private val messages: MessageRepository,
    private val loginRequests: LoginRequestRepository,
    private val attachments: app.singular.media.AttachmentRepository,
    private val storage: app.singular.media.StorageService,
    private val stories: app.singular.story.StoryRepository,
    private val jdbc: JdbcClient,
) {

    /**
     * Deletes uploads that were never attached to anything.
     *
     * Every abandoned compose box leaves an object in storage that nothing will ever
     * reference. Without this they accumulate silently and the bill is the only thing that
     * notices. The grace period is generous because an upload sitting unsent for an hour is
     * a slow typist, not garbage.
     */
    @Scheduled(cron = "0 45 3 * * *")
    fun reapOrphanedUploads() {
        val orphans = attachments.findOrphaned(Instant.now().minus(ORPHAN_GRACE))
        orphans.forEach { attachment ->
            storage.delete(attachment.objectKey)
            attachment.thumbnailKey?.let(storage::delete)
            attachments.delete(attachment.id)
        }
        if (orphans.isNotEmpty()) LOG.info("Reaped {} orphaned uploads", orphans.size)
    }

    /**
     * Reclaims storage for stories past their day.
     *
     * Expiry itself is enforced in the read query, not here — a reaper that falls behind must
     * never leave a story visible past its 24 hours. This only frees bytes.
     */
    @Scheduled(cron = "0 0 * * * *")
    fun reapExpiredStories() {
        val expired = stories.expiredKeys()
        expired.forEach { id ->
            stories.find(id)?.attachmentId?.let { attachmentId ->
                attachments.find(attachmentId)?.let { attachment ->
                    storage.delete(attachment.objectKey)
                    attachment.thumbnailKey?.let(storage::delete)
                    attachments.delete(attachment.id)
                }
            }
            stories.hardDelete(id)
        }
        if (expired.isNotEmpty()) LOG.info("Reaped {} expired stories", expired.size)
    }

    /** Idempotency keys only need to outlive a client's retry window. */
    @Scheduled(cron = "0 15 3 * * *")
    fun reapNonces() {
        val deleted = messages.reapNonces(Instant.now().minus(NONCE_TTL))
        if (deleted > 0) LOG.info("Reaped {} expired message nonces", deleted)
    }

    /**
     * Retires abandoned QR sign-ins.
     *
     * Runs every minute because the window is three minutes: a stale PENDING row left at that
     * status would keep answering scans past its expiry if any query ever forgot its
     * `expires_at` guard. Marking them EXPIRED means the state machine, not a date comparison,
     * is the thing enforcing it. The in-memory sinks are dropped alongside so a burst of
     * abandoned sign-ins can't accumulate.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun reapLoginRequests() {
        val expired = loginRequests.expireStale()
        val deleted = loginRequests.deleteResolvedBefore(Instant.now().minus(LOGIN_RETENTION))
        if (expired > 0 || deleted > 0) {
            LOG.debug("Login requests: {} expired, {} purged", expired, deleted)
        }
    }

    /**
     * Creates partitions ahead of time.
     *
     * There is deliberately no DEFAULT partition on `messages`, because a DEFAULT that has
     * absorbed rows blocks `CREATE TABLE ... PARTITION OF` for any overlapping range — which
     * turns routine maintenance into an outage at the worst possible moment. The cost of that
     * choice is that this job must not be allowed to fail silently: if it stops running,
     * inserts start failing the moment the clock crosses into an uncreated month.
     */
    @Scheduled(cron = "0 30 3 * * *")
    fun ensurePartitions() {
        val today = LocalDate.now().withDayOfMonth(1)
        (0..MONTHS_AHEAD).forEach { offset ->
            val month = today.plusMonths(offset.toLong())
            listOf("messages", "audit_events").forEach { table ->
                jdbc.sql("SELECT ensure_month_partition(:table, :month)")
                    .param("table", table)
                    .param("month", java.sql.Date.valueOf(month))
                    .query(Any::class.java)
                    .optional()
            }
        }
        LOG.debug("Partitions ensured through {}", today.plusMonths(MONTHS_AHEAD.toLong()))
    }

    private companion object {
        val LOG = org.slf4j.LoggerFactory.getLogger(MessageReaper::class.java)!!
        val NONCE_TTL: Duration = Duration.ofHours(24)

        /** Denied and expired sign-ins are kept briefly so the audit trail has something to
         *  point at, then purged. Approved ones are already CONSUMED and carry no secrets. */
        val LOGIN_RETENTION: Duration = Duration.ofHours(1)

        /** How long an unsent upload is kept before it counts as abandoned. */
        val ORPHAN_GRACE: Duration = Duration.ofHours(6)
        const val MONTHS_AHEAD = 6
    }
}
