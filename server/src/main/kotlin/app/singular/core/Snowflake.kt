package app.singular.core

import app.singular.config.SingularProperties
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * 64-bit time-sortable id generator.
 *
 * ```
 *  63                                                              0
 *  | 1 unused | 41 bits: ms since EPOCH | 10 bits: node | 12: seq |
 *    sign       ~69 years of range        1024 nodes      4096/ms/node
 * ```
 *
 * Sorting by id is sorting by creation time, which means one index serves both pagination and
 * chronology, and every id carries its own timestamp — no `created_at` lookup needed to know
 * when something was made.
 *
 * The bit layout is fixed forever once you have data. [EPOCH] especially: changing it
 * retroactively reinterprets every id you have ever minted.
 */
@Component
class Snowflake(props: SingularProperties) {

    private val nodeId: Long = props.nodeId

    init {
        require(nodeId in 0..MAX_NODE_ID) {
            "singular.node-id must be in 0..$MAX_NODE_ID, was $nodeId"
        }
    }

    private var lastTimestamp = -1L
    private var sequence = 0L

    @Synchronized
    fun next(): Long {
        var now = System.currentTimeMillis()

        // Clock went backwards — NTP correction, VM migration, someone running `date`.
        // Spinning is correct: minting ids from a rewound clock would produce values that
        // collide with ones already committed.
        if (now < lastTimestamp) {
            val drift = lastTimestamp - now
            check(drift <= MAX_TOLERATED_DRIFT_MS) {
                "Clock moved backwards by ${drift}ms — refusing to mint ids. Check NTP."
            }
            while (now < lastTimestamp) {
                parkBriefly()
                now = System.currentTimeMillis()
            }
        }

        if (now == lastTimestamp) {
            sequence = (sequence + 1) and MAX_SEQUENCE
            // 4096 ids in the same millisecond: wait for the next tick rather than collide.
            // This is not a doomsday path — 4096 ids in one millisecond on one node is a
            // burst the pool and Postgres can't feed anyway — but when it happens, burning
            // CPU that the request-handling threads need is strictly worse than parking.
            if (sequence == 0L) {
                while (now <= lastTimestamp) {
                    parkBriefly()
                    now = System.currentTimeMillis()
                }
            }
        } else {
            sequence = 0L
        }

        lastTimestamp = now
        return ((now - EPOCH) shl TIMESTAMP_SHIFT) or (nodeId shl NODE_SHIFT) or sequence
    }

    companion object {
        /** 2025-01-01T00:00:00Z. Immutable once any id exists. */
        const val EPOCH = 1_735_689_600_000L

        private const val NODE_BITS = 10
        private const val SEQUENCE_BITS = 12
        private const val MAX_NODE_ID = (1L shl NODE_BITS) - 1        // 1023
        private const val MAX_SEQUENCE = (1L shl SEQUENCE_BITS) - 1   // 4095
        private const val NODE_SHIFT = SEQUENCE_BITS
        private const val TIMESTAMP_SHIFT = SEQUENCE_BITS + NODE_BITS
        private const val MAX_TOLERATED_DRIFT_MS = 5_000L

        /**
         * How long a waiter parks between clock rechecks.
         *
         * `Thread.onSpinWait` in these loops burns a full core per waiting id-mint for as long
         * as the condition holds — a core that virtual-thread carriers and the request path
         * need. Parking instead costs zero CPU. It doesn't fully solve carrier pinning on Java
         * 21 (sleeping while holding the monitor still pins the carrier until the wait ends;
         * JEP 491 removes that in Java 24+), but a pinned-and-parked carrier is harmless
         * compared to a pinned-and-spinning one, and the waits here are sub-millisecond by
         * construction — they only exist to cross a single clock tick.
         *
         * 200µs keeps the wait's resolution comfortably inside a millisecond (we only ever
         * need to cross one) while costing at most ~5 empty wakeups per ms — negligible next
         * to a JDBC round trip, and it never runs at all in the common case.
         */
        private const val PARK_NANOS = 200_000L

        fun timestampOf(id: Long): Instant =
            Instant.ofEpochMilli((id ushr TIMESTAMP_SHIFT) + EPOCH)

        fun nodeOf(id: Long): Long = (id ushr NODE_SHIFT) and MAX_NODE_ID

        /**
         * Lowest id that could have been minted at [at]. Useful for range-scanning a
         * time window without touching `created_at`.
         */
        fun floorFor(at: Instant): Long =
            (at.toEpochMilli() - EPOCH).coerceAtLeast(0) shl TIMESTAMP_SHIFT
    }

    /** Sleeps a fraction of a millisecond instead of spinning. See [PARK_NANOS]. */
    private fun parkBriefly() {
        Thread.sleep(0, PARK_NANOS.toInt())
    }
}
