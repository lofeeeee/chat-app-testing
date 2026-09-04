package app.singular.ratelimit

import app.singular.config.SingularProperties
import app.singular.core.RateLimited
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max

/**
 * Node-local token buckets.
 *
 * ## How it works
 *
 * Each `(scope, key)` pair owns a bucket with `capacity` tokens, refilled at
 * `refillPerMinute`. Refill is computed lazily from elapsed time on each check — there is no
 * background thread and no timer, which means zero cost when a scope is idle and no thread
 * churn when nothing is being limited. A burst up to `capacity` succeeds instantly; sustained
 * traffic settles at the refill rate. That shape matches how these endpoints are actually
 * attacked: fast hammering, not patient metering.
 *
 * ## Why node-local is an honest default
 *
 * On a single instance this is exactly right. On N instances each node keeps its own bucket,
 * so the effective limit is `N ×` the configured one. That is stated here rather than hidden,
 * because the alternative — pretending a local counter is a global one — is the kind of
 * inaccuracy that gets discovered during an incident. When the multi-node deployment arrives,
 * the same [RateLimiter] interface gets an implementation backed by the shared store, and
 * the controller call sites don't change at all.
 *
 * ## Eviction
 *
 * Buckets for keys that go quiet are swept by the reaper (see [sweepIdle]); without that, an
 * attacker rotating through spoofed `X-Forwarded-For` values would grow the map without
 * bound. Eviction only drops *idle* buckets — an actively refilled bucket with a subscriber
 * behind it is never at risk, and a swept bucket that's hit again is simply recreated empty,
 * which is the attacker's problem, not ours.
 */
@Component
class TokenBucketRateLimiter(
    private val props: SingularProperties,
    /** Injectable so tests can simulate time passing. Production uses the real clock. */
    private val clock: () -> Long = System::nanoTime,
) : RateLimiter {

    private val buckets = ConcurrentHashMap<String, Bucket>()

    override fun acquireOrThrow(scope: String, key: String) {
        val decision = tryAcquire(scope, key)
        if (!decision.allowed) {
            throw RateLimited(decision.retryAfterSeconds ?: 1L)
        }
    }

    override fun tryAcquire(scope: String, key: String): RateLimitDecision {
        val policy = props.rateLimit.policyFor(scope)
        val bucket = buckets.computeIfAbsent("$scope:$key") { Bucket(policy.capacity.toDouble()) }
        return bucket.tryConsume(policy, clock())
    }

    /**
     * Drops buckets idle longer than [maxIdleNanos]. Called from the reaper's schedule —
     * see `MessageReaper.sweepRateLimitBuckets`.
     *
     * @return how many buckets were evicted, for the log line.
     */
    fun sweepIdle(maxIdleNanos: Long): Int {
        val cutoff = clock() - maxIdleNanos
        val doomed = buckets.entries.filter { it.value.lastTouchedNanos < cutoff }
        doomed.forEach { buckets.remove(it.key, it.value) }
        return doomed.size
    }

    /** Number of live buckets — exposed for metrics/health. */
    fun trackedBucketCount(): Int = buckets.size

    /**
     * Mutable bucket state. All mutation is `@Synchronized` on the bucket itself: two threads
     * racing to spend the last token must not both succeed, and the bucket is small enough
     * that lock contention is a non-issue next to the request it guards.
     */
    private class Bucket(var tokens: Double) {
        var lastTouchedNanos: Long = 0
        var lastRefillNanos: Long = 0

        @Synchronized
        fun tryConsume(policy: SingularProperties.RateLimit.Policy, nowNanos: Long): RateLimitDecision {
            if (lastRefillNanos == 0L) lastRefillNanos = nowNanos
            lastTouchedNanos = nowNanos

            // Lazy refill: tokens accrued since the last look, capped at capacity.
            val elapsedMinutes = (nowNanos - lastRefillNanos) / 60_000_000_000.0
            if (elapsedMinutes > 0) {
                tokens = max(tokens + elapsedMinutes * policy.refillPerMinute, 0.0)
                    .coerceAtMost(policy.capacity.toDouble())
                lastRefillNanos = nowNanos
            }

            if (tokens >= 1.0) {
                tokens -= 1.0
                return RateLimitDecision(allowed = true, retryAfterSeconds = null)
            }

            // How long until one whole token exists again. ceil, because a fraction of a
            // second is still a second the client must wait.
            val deficit = 1.0 - tokens
            val seconds = ceil(deficit / policy.refillPerMinute * 60.0).toLong().coerceAtLeast(1L)
            return RateLimitDecision(allowed = false, retryAfterSeconds = seconds)
        }
    }
}
