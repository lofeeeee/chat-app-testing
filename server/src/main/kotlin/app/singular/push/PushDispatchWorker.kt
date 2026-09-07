package app.singular.push

import app.singular.config.SingularProperties
import app.singular.schedule.DistributedLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Drains the push outbox.
 *
 * The send path enqueues (after commit — see [PushFanout]) and this worker dispatches, so a
 * slow or cranky provider never sits inside a GraphQL mutation, and a crash mid-batch loses
 * nothing: the rows are still there, still due.
 *
 * Retry semantics:
 *  * A transport returning `false` — the provider says the token is dead — marks the token
 *    invalid and the row dead in one step. Retrying a dead token is wasted work.
 *  * A [PushRetryable] (or any unexpected exception) is a transient failure: the row is
 *    rescheduled with exponential backoff, up to [SingularProperties.Push.maxAttempts].
 *  * Anything else — a misdelivered row, a corrupt payload — is treated as a hard failure so
 *    a poison message can't wedge the queue.
 */
@Component
class PushDispatchWorker(
    private val outbox: PushOutboxRepository,
    private val tokens: PushTokenRepository,
    private val transports: List<PushTransport>,
    private val locks: DistributedLock,
    private val props: SingularProperties,
) {

    /**
     * Every two seconds, while there is work. The [DistributedLock] lease means exactly one
     * node drains at a time; the others' ticks are no-ops. Combined with
     * `FOR UPDATE SKIP LOCKED` on the batch query, a lease expiring mid-batch costs at most
     * duplicate sends (Apple and Google both dedupe by token+message window), never drops.
     */
    @Scheduled(fixedDelay = 2_000, initialDelay = 5_000)
    fun dispatch() {
        locks.tryLock("push-dispatch", LEASE)?.use {
            var dispatched = 0
            while (true) {
                val batch = outbox.dueBatch(props.push.batchSize)
                if (batch.isEmpty()) break

                batch.forEach { row ->
                    runCatching { attempt(row) }
                        .onFailure { e ->
                            // Transient by construction or unknown — both retry. An unknown
                            // exception from a transport is more likely a provider blip
                            // than a logic bug in the two-line send path.
                            outbox.markAttemptFailed(row.id, row.attempts + 1, props.push.maxAttempts)
                            if (e !is PushRetryable) {
                                LOG.warn("Push dispatch for row {} failed unexpectedly — retrying", row.id, e)
                            }
                        }
                    dispatched++
                }

                // Stop once a batch comes back short — the queue is drained for now and the
                // next tick is two seconds away.
                if (batch.size < props.push.batchSize) break
            }
            if (dispatched > 0) LOG.debug("Push dispatch: {} rows processed", dispatched)
        }
    }

    // No @Transactional, deliberately: each repository statement is individually atomic, and
    // a wrapping transaction would hold the FOR UPDATE row locks across provider round trips —
    // exactly the coupling between chat speed and provider latency the outbox exists to break.
    fun attempt(row: PushOutboxRow) {
        val transport = transports.firstOrNull { it.platform == row.platform }
        if (transport == null) {
            // No transport for this platform is registered (credentials absent). The row
            // should not spin forever — treat as a hard failure and move on.
            LOG.warn("No transport registered for {} (row {}) — giving up on this row", row.platform, row.id)
            outbox.markAttemptFailed(row.id, props.push.maxAttempts, props.push.maxAttempts)
            return
        }

        val alive = transport.send(
            row.token,
            PushMessage(
                userId = row.userId,
                title = row.title,
                body = row.body,
                channelId = row.channelId,
                messageId = row.messageId,
            ),
        )

        if (alive) {
            outbox.markDelivered(row.id)
        } else {
            tokens.markInvalid(row.token)
            outbox.markAttemptFailed(row.id, props.push.maxAttempts, props.push.maxAttempts)
        }
    }

    /** Settled rows are queue history, not archive — the provider's logs are the archive. */
    @Scheduled(cron = "0 20 4 * * *")
    fun reapSettled() = locks.tryLock("push-reap", LEASE)?.use {
        val deleted = outbox.reapSettled(Instant.now().minus(RETENTION))
        if (deleted > 0) LOG.debug("Reaped {} settled push rows", deleted)
    } ?: Unit

    private companion object {
        val LOG = LoggerFactory.getLogger(PushDispatchWorker::class.java)!!

        /** Long enough for a full batch of provider round trips; short enough that an expired
         *  lease only delays the next batch by seconds. */
        val LEASE: Duration = Duration.ofSeconds(60)

        /** How long delivered/failed rows stick around for inspection. */
        val RETENTION: Duration = Duration.ofDays(7)
    }
}
