package app.singular.schedule

import io.lettuce.core.api.StatefulRedisConnection
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.UUID

/**
 * A lease that lets exactly one node run a scheduled job at a time.
 *
 * The reaper's jobs are idempotent, so on a single node nothing here matters. On N nodes, all
 * N fire every cron tick — mostly harmless (they'd just redo each other's work), except each
 * would delete storage objects and reaper rows the others are iterating, and every job would
 * take N× the wall clock. A lease that costs one SET NX makes that a non-problem.
 *
 * ## Why SET NX PX with a token, released by compare-and-delete
 *
 * The lock value is a random token unique to this acquisition. Release only deletes the key
 * if it still holds our token — the classic pattern for a lock whose holder can outlive its
 * own TTL: if node A's lease expires mid-job and node B acquires it, A's later release must
 * NOT delete B's lock. A naive `DEL` would do exactly that, and the failure needs a very
 * specific pause pattern to reproduce — the worst kind of bug.
 *
 * ## What the TTL is for
 *
 * Not correctness of the jobs (they're idempotent — if the lock lapses and two nodes run, the
 * result is duplicate work, never corruption) but liveness: a node that dies mid-job must not
 * hold the lease forever. The TTL is the lock's own crash detector.
 *
 * ## Locks are held, not kept alive
 *
 * No renewal. Every job here runs in well under the TTL; if one ever grows past it, the right
 * fix is splitting the job, not adding a watchdog thread.
 */
@Component
class DistributedLock(
    private val redis: StatefulRedisConnection<String, String>,
) {

    private val sync = redis.sync()

    /**
     * Acquires the lease named [name], or returns null if another node holds it.
     *
     * The returned handle must be closed (ideally via `use {}`), releasing the lease.
     */
    fun tryLock(name: String, ttl: Duration): Handle? {
        val token = UUID.randomUUID().toString()
        val acquired = sync.set(
            "singular:lock:$name",
            token,
            io.lettuce.core.SetArgs().nx().px(ttl.toMillis()),
        )
        return if (acquired != null) Handle(name, token) else null
    }

    /**
     * The lease. Closeable so `tryLock(...)?.use { ... }` reads exactly like a local lock,
     * with the skip path being a simple null check.
     */
    inner class Handle(private val name: String, private val token: String) : AutoCloseable {
        override fun close() {
            // Compare-and-delete: only remove the key if it still holds OUR token. If the
            // lease expired and someone else owns it now, deleting it would hand the job to a
            // third node while the second is mid-run — the exact race the token prevents.
            val released = sync.eval<Long>(
                RELEASE_SCRIPT,
                io.lettuce.core.ScriptOutputType.INTEGER,
                arrayOf("singular:lock:$name"),
                token,
            )
            if (released != 1L) {
                LOG.debug("Lock '{}' was not released by its owner (lease expired) — nothing to do", name)
            }
        }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(DistributedLock::class.java)!!

        /** Redis guarantees Lua runs atomically — the check and the delete cannot interleave. */
        const val RELEASE_SCRIPT =
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end"
    }
}
