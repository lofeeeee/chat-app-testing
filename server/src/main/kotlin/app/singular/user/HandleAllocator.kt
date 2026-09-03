package app.singular.user

import app.singular.core.NameExhausted
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * Allocates the `#0971` half of a legacy-style handle.
 *
 * How the original worked, since it's easy to get wrong from memory:
 *
 *   * Uniqueness is on the PAIR `(lower(username), discriminator)` — never on username alone.
 *     Up to 9,999 people can share a name.
 *   * The number is drawn at random from whatever is still free **for that specific name**.
 *   * You never carry it with you. Renaming away releases it; renaming back draws a fresh one.
 *     So `alex#0971` -> `sam#4412` -> back to `alex` yields `alex#1239`, because `#0971` was
 *     released the moment you left and may already be gone.
 *
 * Two deliberate departures from the original:
 *
 *   1. Released pairs are quarantined for [QUARANTINE]. Discord's version let anyone grab a
 *      freed handle the instant its owner renamed, which made impersonation trivial and was a
 *      real factor in the scheme's retirement in 2023.
 *   2. `#0000` is reserved for system accounts, so the usable space is 1..9999.
 */
@Component
class HandleAllocator(private val users: UserRepository) {

    /**
     * @param attempt inserts the candidate and returns false if the unique index rejected it.
     *                Passing the insert in keeps allocation and creation in one atomic step —
     *                a check-then-insert would race with concurrent registrations.
     */
    fun allocate(username: String, attempt: (Short) -> Boolean): Short {
        // Fast path. While a name is sparsely used, a blind random draw almost always lands,
        // and costs one insert instead of a scan.
        repeat(RANDOM_ATTEMPTS) {
            val candidate = Random.nextInt(MIN, MAX + 1).toShort()
            if (attempt(candidate)) return candidate
        }

        // Crowded name. Enumerate what's actually free — worst case 9,999 smallints, which is
        // one index-only scan, not a table scan.
        val unavailable = buildSet {
            addAll(users.takenDiscriminators(username))
            addAll(users.quarantinedDiscriminators(username, Instant.now().minus(QUARANTINE)))
        }

        val free = (MIN..MAX).filter { it.toShort() !in unavailable }
        if (free.isEmpty()) throw NameExhausted(username)

        // Shuffle rather than take the lowest: sequential assignment leaks registration order
        // and makes #0001 a status symbol worth squatting.
        for (candidate in free.shuffled()) {
            if (attempt(candidate.toShort())) return candidate.toShort()
        }

        // Everything free at scan time got taken underneath us. Vanishingly unlikely, but
        // surfacing it honestly beats an infinite retry loop.
        throw NameExhausted(username)
    }

    private companion object {
        const val MIN = 1
        const val MAX = 9999          // #0000 reserved for system accounts
        const val RANDOM_ATTEMPTS = 12
        val QUARANTINE: Duration = Duration.ofDays(30)
    }
}
