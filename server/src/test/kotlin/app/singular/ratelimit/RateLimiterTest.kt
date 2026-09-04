package app.singular.ratelimit

import app.singular.config.SingularProperties
import app.singular.core.RateLimited
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Token-bucket tests. Time is injected as a mutable counter of nanoseconds, because a test
 * that sleeps for wall-clock seconds is slow and flaky in equal measure.
 */
class RateLimiterTest {

    /** A clock the test advances by hand. One tick = one nanosecond unless said otherwise. */
    private class FakeTime {
        var nowNanos = 1_000_000_000L
        fun advanceMinutes(m: Double) { nowNanos += (m * 60_000_000_000L).toLong() }
    }

    private fun limiter(policy: SingularProperties.RateLimit.Policy, time: FakeTime): Pair<TokenBucketRateLimiter, FakeTime> {
        val props = SingularProperties(
            rateLimit = SingularProperties.RateLimit(
                login = policy, // reuse the login slot; policyFor maps scope names to fields
            )
        )
        return TokenBucketRateLimiter(props, clock = { time.nowNanos }) to time
    }

    @Test
    fun `burst up to capacity succeeds then refuses`() {
        val (limiter, _) = limiter(SingularProperties.RateLimit.Policy(capacity = 3, refillPerMinute = 1.0), FakeTime())

        repeat(3) { limiter.acquireOrThrow("login", "1.2.3.4") }

        val denied = assertFailsWith<RateLimited> { limiter.acquireOrThrow("login", "1.2.3.4") }
        // Refill is 1 token/min, so a full token is one minute away.
        assertEquals(60L, denied.retryAfterSeconds)
    }

    @Test
    fun `tokens refill over time and cap at capacity`() {
        val (limiter, time) = limiter(SingularProperties.RateLimit.Policy(capacity = 2, refillPerMinute = 60.0), FakeTime())

        repeat(2) { limiter.acquireOrThrow("login", "ip") }           // drain
        time.advanceMinutes(0.5)                                       // 30 seconds -> 30 tokens accrued, capped at 2
        repeat(2) { limiter.acquireOrThrow("login", "ip") }           // full again
        assertFailsWith<RateLimited> { limiter.acquireOrThrow("login", "ip") }

        time.advanceMinutes(100.0)                                     // long idle: still only capacity
        repeat(2) { limiter.acquireOrThrow("login", "ip") }
        assertFailsWith<RateLimited> { limiter.acquireOrThrow("login", "ip") }
    }

    @Test
    fun `keys are isolated - one abuser does not throttle a neighbour`() {
        val (limiter, _) = limiter(SingularProperties.RateLimit.Policy(capacity = 1, refillPerMinute = 0.1), FakeTime())

        limiter.acquireOrThrow("login", "attacker")
        assertFailsWith<RateLimited> { limiter.acquireOrThrow("login", "attacker") }
        limiter.acquireOrThrow("login", "victim") // unaffected
    }

    @Test
    fun `retryAfter is rounded up and never zero`() {
        val (limiter, time) = limiter(SingularProperties.RateLimit.Policy(capacity = 1, refillPerMinute = 60.0), FakeTime())

        limiter.acquireOrThrow("login", "ip")
        time.advanceMinutes(0.01) // ~0.6 tokens accrued -> deficit ~0.4 -> ~0.4s, must report >= 1
        val denied = assertFailsWith<RateLimited> { limiter.acquireOrThrow("login", "ip") }
        assertTrue(denied.retryAfterSeconds >= 1, "retryAfterSeconds must be at least 1, was ${denied.retryAfterSeconds}")
    }

    @Test
    fun `unknown scope fails closed`() {
        val (limiter, _) = limiter(SingularProperties.RateLimit.Policy(capacity = 1, refillPerMinute = 1.0), FakeTime())
        assertFailsWith<IllegalStateException> { limiter.acquireOrThrow("no-such-scope", "ip") }
    }

    @Test
    fun `sweep evicts only idle buckets`() {
        val (limiter, time) = limiter(SingularProperties.RateLimit.Policy(capacity = 100, refillPerMinute = 1.0), FakeTime())

        limiter.acquireOrThrow("login", "stale")
        time.advanceMinutes(2.0)
        limiter.acquireOrThrow("login", "fresh") // touched *after* the stale one

        val evicted = limiter.sweepIdle(60_000_000_000L) // 1 minute idle cutoff
        assertEquals(1, evicted)
        assertEquals(1, limiter.trackedBucketCount())

        // The swept bucket restarts empty rather than remembering its debt.
        limiter.acquireOrThrow("login", "stale")
    }
}
