package app.singular.user

import app.singular.audit.AuditLog
import app.singular.core.InvalidInput
import app.singular.core.NotFound
import app.singular.domain.AuditAction
import app.singular.domain.User
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Account identity — currently just the one operation that is genuinely tricky.
 *
 * Renaming is not a field update. The username is half of a unique pair, so changing it can
 * collide, has to release what it leaves behind, and has to decide what number you come out
 * with. Doing that inline in a controller is how one of those three steps gets forgotten.
 */
@Service
class UserService(
    private val users: UserRepository,
    private val handles: HandleAllocator,
    private val audit: AuditLog,
) {

    /**
     * Changes the username, keeping your discriminator where possible.
     *
     * The rule, in full:
     *
     *   * `asep#1234` renaming to `hehe` becomes `hehe#1234` — your number comes with you,
     *     because nobody was using that pair.
     *   * Renaming back to `asep` gets `asep#1234` again if it is still free. Your own
     *     quarantine row does not block you; it exists to stop other people taking it.
     *   * If someone else claimed `asep#1234` while you were away, you come back as
     *     `asep#5819` — a fresh draw. Several people can be `asep`; only one can be
     *     `asep#1234`.
     *
     * Transactional because it is two writes — the rename and the quarantine of the handle
     * being vacated. Committing the rename without the quarantine would leave the old handle
     * immediately claimable by anyone watching, which is the exact thing the window prevents.
     */
    @Transactional
    fun changeUsername(userId: Long, requested: String): User {
        val username = requested.trim()
        validate(username)

        val current = users.findById(userId) ?: throw NotFound("Account")
        if (current.username.equals(username, ignoreCase = true) &&
            current.username == username
        ) {
            // Genuinely nothing to do. Returning early rather than "succeeding" through the
            // rename path avoids quarantining the handle they still hold.
            return current
        }

        val previousName = current.username
        val previousDiscriminator = current.discriminator

        // Try to keep the number first. This is the whole difference from Discord's scheme,
        // and it is one call — if the pair is taken the unique index says so.
        val discriminator =
            if (users.tryRename(userId, username, previousDiscriminator)) previousDiscriminator
            else handles.allocate(username, forUserId = userId) { candidate ->
                users.tryRename(userId, username, candidate)
            }

        // Release what they left. Only meaningful when the name actually changed — a
        // case-only edit ("asep" -> "Asep") keeps the same pair and vacates nothing.
        if (!previousName.equals(username, ignoreCase = true)) {
            users.quarantine(previousName, previousDiscriminator, releasedBy = userId)
        }

        // Both handles recorded. A rename is the one profile edit that changes how people find
        // you, so "who was this account before" has to be answerable after the fact.
        audit.record(
            userId,
            AuditAction.USERNAME_CHANGE,
            targetId = userId,
            changes = mapOf(
                "from" to "$previousName#${previousDiscriminator.pad()}",
                "to" to "$username#${discriminator.pad()}",
            ),
        )
        return users.findById(userId) ?: throw NotFound("Account")
    }

    /**
     * The same rule registration uses.
     *
     * Duplicated deliberately rather than reaching into AuthService: the alternative is a
     * service dependency that exists only to share a regex, and a rename that silently accepts
     * a name registration would have rejected is worse than two copies of one line.
     */
    private fun validate(username: String) {
        if (!USERNAME_RE.matches(username)) {
            throw InvalidInput(
                "Usernames are 2 to 32 characters, using letters, numbers, _ and . only."
            )
        }
    }

    /** `7` reads as `#0007`. Four digits always, so handles line up and can't be misread. */
    private fun Short.pad(): String = toString().padStart(4, '0')

    private companion object {
        val USERNAME_RE = Regex("^[A-Za-z0-9_.]{2,32}$")
    }
}
