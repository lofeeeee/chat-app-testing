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
 *
 * Three deliberate departures from the original:
 *
 *   1. **Your number follows you where it can.** Discord released the discriminator the instant
 *      you renamed and drew a fresh one if you came back. Here a rename keeps your number when
 *      that pair is free under the new name, so `asep#1234` -> `hehe#1234` -> back to
 *      `asep#1234`. You only get a new one when someone took the pair while you were away:
 *      then it's `asep#5819`. See [UserService.changeUsername].
 *   2. Released pairs are quarantined for [QUARANTINE] — but only against *other* people.
 *      Discord's version let anyone grab a freed handle the instant its owner renamed, which
 *      made impersonation trivial and was a real factor in the scheme's retirement in 2023.
 *      Applying that window to the previous owner too would defeat rule 1.
 *   3. `#0000` is reserved for system accounts, so the usable space is 1..9999.
 */
@Component
class HandleAllocator(private val users: UserRepository) {

    /**
     * @param forUserId whoever the handle is for. Exempts them from their own quarantine rows.
     * @param attempt   writes the candidate and returns false if the unique index rejected it.
     *                  Passing the write in keeps allocation and persistence in one atomic
     *                  step — a check-then-write would race with concurrent registrations.
     */
    fun allocate(
        username: String,
        forUserId: Long? = null,
        attempt: (Short) -> Boolean,
    ): Short {
        // Loaded up front, and consulted by BOTH phases below.
        //
        // The quarantine cannot be left to the slow path. A released pair is by definition
        // absent from `users`, so the unique index has no opinion on it — a blind random draw
        // would take one happily, and since the draw succeeds almost every time, the window
        // would protect essentially nothing. One indexed read is the price of it working.
        val quarantined = users.quarantinedDiscriminators(
            username,
            Instant.now().minus(QUARANTINE),
            exceptUserId = forUserId,
        )

        // Fast path. While a name is sparsely used, a random draw almost always lands, and
        // costs one write instead of a scan.
        repeat(RANDOM_ATTEMPTS) {
            val candidate = Random.nextInt(MIN, MAX + 1).toShort()
            if (candidate !in quarantined && attempt(candidate)) return candidate
        }

        // Crowded name. Enumerate what's actually free — worst case 9,999 smallints, which is
        // one index-only scan, not a table scan.
        val unavailable = users.takenDiscriminators(username) + quarantined
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
