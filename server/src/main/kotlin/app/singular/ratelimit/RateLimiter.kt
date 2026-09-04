package app.singular.ratelimit

import app.singular.core.RateLimited
import app.singular.config.SingularProperties

/**
 * One decision, in one place: is this caller allowed to do this right now?
 *
 * Rate limiting is a platform obligation the blueprint lists under "neither app advertises
 * these" — every mutation needs one, because every mutation is a thing an attacker can
 * hammer. The check is deliberately explicit at the top of each controller method rather
 * than hidden in an interceptor: an interceptor would have to parse the GraphQL operation
 * name out of the query text on every request, and a mistake there would silently leave an
 * endpoint unprotected. A visible `acquireOrThrow` line cannot be forgotten by a refactor
 * without being visible in the diff.
 *
 * Implementations:
 *   * [TokenBucketRateLimiter] — node-local, in-memory. The default; correct for one
 *     instance, and the honest cost of running it multi-node is that the effective limit
 *     multiplies by node count.
 *   * a Valkey-backed implementation, added with the multi-node wiring, which shares the
 *     counter across nodes so the limit means what it says.
 *
 * Keying is the caller's judgement call and is documented at each call site: anonymous
 * endpoints key on client IP (the only identity an unauthenticated caller has), authenticated
 * ones on user id (so a single account misbehaving doesn't throttle everybody behind the same
 * NAT).
 */
interface RateLimiter {

    /**
     * Consumes one token from the bucket named by `(scope, key)`.
     *
     * @param scope which policy applies, e.g. `"login"`, `"qr-create"`. Policies live in
     *        `singular.rate-limit.*` — see [SingularProperties.RateLimit].
     * @param key  the identity being limited — an IP address for anonymous endpoints, a
     *        user id once authenticated.
     * @throws RateLimited when the bucket is empty, carrying how long to wait.
     */
    fun acquireOrThrow(scope: String, key: String)

    /**
     * Same as [acquireOrThrow], for callers that want the decision without the exception
     * control flow (metrics, health probes).
     */
    fun tryAcquire(scope: String, key: String): RateLimitDecision
}

/** The outcome of a check, with what the caller needs to tell the client when refused. */
data class RateLimitDecision(val allowed: Boolean, val retryAfterSeconds: Long?)
