package app.singular.auth

import app.singular.audit.AuditLog
import app.singular.config.SingularProperties
import app.singular.core.Conflict
import app.singular.core.InvalidCredentials
import app.singular.core.InvalidInput
import app.singular.core.NotAuthenticated
import app.singular.core.NotFound
import app.singular.core.Snowflake
import app.singular.domain.AuditAction
import app.singular.domain.ClientContext
import app.singular.domain.SessionOrigin
import app.singular.domain.User
import app.singular.security.AccessTokens
import app.singular.security.Crypto
import app.singular.user.HandleAllocator
import app.singular.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

data class AuthResult(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Int,
    val user: User,
)

@Service
class AuthService(
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val handles: HandleAllocator,
    private val crypto: Crypto,
    private val accessTokens: AccessTokens,
    private val snowflake: Snowflake,
    private val audit: AuditLog,
    private val props: SingularProperties,
) {

    @Transactional
    fun register(
        username: String,
        email: String,
        password: String,
        displayName: String?,
        client: ClientContext,
    ): AuthResult {
        validateUsername(username)
        validateEmail(email)
        validatePassword(password)

        val emailBidx = crypto.blindIndex(email)
        if (users.emailIndexExists(emailBidx)) {
            // Same shape of message as any other conflict. Saying "this email exists" turns
            // registration into an account-enumeration oracle.
            throw Conflict("That email is already registered.")
        }

        val userId = snowflake.next()
        val passwordHash = crypto.hashPassword(password)

        // Allocation and insertion are the same operation — the unique index arbitrates races
        // between concurrent registrations of the same name, so no check-then-insert window.
        val discriminator = handles.allocate(username) { candidate ->
            users.tryInsert(
                id = userId,
                username = username,
                discriminator = candidate,
                displayName = displayName ?: username,
                emailEnc = email.toByteArray(),   // phase 5: AES-256-GCM ciphertext goes here
                emailBidx = emailBidx,
                passwordHash = passwordHash,
            )
        }

        val user = users.findById(userId)
            ?: error("User $userId vanished between insert and read")

        audit.record(userId, AuditAction.REGISTER, changes = mapOf("handle" to user.handle))
        LOG.info("Registered {} (#{} allocated for name '{}')", user.handle, discriminator, username)

        val (result, sessionId) = issueSession(user, client, familyId = null)
        audit.record(userId, AuditAction.LOGIN, sessionId = sessionId)
        return result
    }

    /**
     * Email and password only.
     *
     * The `name#0971` handle is an *addressing* identity — how people find and add each other —
     * not a credential. Discord draws the same line, and it matters: handles are meant to be
     * shared publicly, and a discriminator gets reallocated whenever someone renames. Letting
     * one sign in would mean a credential that other people are encouraged to hand out and
     * that can silently change owner.
     */
    @Transactional
    fun login(email: String, password: String, client: ClientContext): AuthResult {
        val credentials = users.findCredentialsByEmailIndex(crypto.blindIndex(email))

        // Verify against a dummy hash when the account doesn't exist, so a miss costs the same
        // as a hit. See Crypto.dummyHash.
        val ok = if (credentials != null) {
            crypto.verifyPassword(password, credentials.passwordHash)
        } else {
            crypto.verifyPassword(password, crypto.dummyHash)
            false
        }

        if (credentials == null || !ok) {
            credentials?.let { audit.record(it.id, AuditAction.LOGIN_FAILED) }
            throw InvalidCredentials()
        }

        // Parameters were raised since this hash was made. Now — holding the plaintext — is the
        // only moment we can ever upgrade it.
        if (crypto.passwordNeedsRehash(credentials.passwordHash)) {
            users.updatePasswordHash(credentials.id, crypto.hashPassword(password))
            LOG.info("Upgraded Argon2 parameters for user={}", credentials.id)
        }

        val user = users.findById(credentials.id) ?: throw NotFound("Account")
        val (result, sessionId) = issueSession(user, client, familyId = null)
        audit.record(user.id, AuditAction.LOGIN, sessionId = sessionId)
        return result
    }

    /**
     * Rotating refresh.
     *
     * Every refresh mints a new token and marks its predecessor superseded. Presenting an
     * already-superseded token means it was captured and replayed — and we cannot tell whether
     * the replay came from a thief or from the legitimate client retrying. The safe reading is
     * theft, so the entire rotation family is revoked and everyone signs in again.
     *
     * That is the intended outcome: a stolen refresh token becomes *detectable* instead of
     * silently permanent.
     */
    // noRollbackFor is the whole point of this annotation here: the reuse branch revokes the
    // token family and THEN throws. Without it the throw rolls the revocation back, and a
    // detected token theft leaves every stolen session live — the exact opposite of intended.
    @Transactional(noRollbackFor = [NotAuthenticated::class])
    fun refresh(refreshToken: String, client: ClientContext): AuthResult {
        val presented = sessions.findByTokenHash(crypto.sha256(refreshToken))
            ?: throw NotAuthenticated()

        if (presented.supersededBy != null) {
            val revoked = sessions.revokeFamily(presented.familyId)
            audit.record(
                presented.userId,
                AuditAction.TOKEN_REUSE_DETECTED,
                sessionId = presented.id,
                changes = mapOf(
                    "familyId" to presented.familyId.toString(),
                    "sessionsRevoked" to revoked,
                ),
            )
            LOG.warn(
                "Refresh token reuse detected: user={} family={} — revoked {} session(s)",
                presented.userId, presented.familyId, revoked,
            )
            throw NotAuthenticated()
        }

        if (presented.revokedAt != null || !presented.expiresAt.isAfter(Instant.now())) {
            throw NotAuthenticated()
        }

        val user = users.findById(presented.userId) ?: throw NotAuthenticated()

        // Order matters: create the successor first, link the predecessor second. A crash
        // between the two leaves the old token still usable, which is recoverable. The reverse
        // order would strand the client with neither token.
        val (result, newSessionId) =
            issueSession(user, client, presented.familyId, SessionOrigin.REFRESH)
        sessions.markSuperseded(presented.id, newSessionId)
        sessions.revoke(presented.id)

        audit.record(user.id, AuditAction.TOKEN_REFRESH, sessionId = newSessionId)
        return result
    }

    @Transactional
    fun logout(refreshToken: String): Boolean {
        val session = sessions.findByTokenHash(crypto.sha256(refreshToken)) ?: return false
        // Revoke the family, not just this session: signing out on one device should not leave
        // a rotated descendant of the same login alive.
        sessions.revokeFamily(session.familyId)
        audit.record(session.userId, AuditAction.LOGOUT, sessionId = session.id)
        return true
    }

    /**
     * Mints a session for a user who proved themselves by approving a QR sign-in on an already
     * trusted device.
     *
     * No password is checked here, and that is correct: the proof of identity happened on the
     * phone, which was already authenticated and showed the user exactly which device, IP and
     * location they were admitting. Callers must not expose this without that approval step.
     */
    @Transactional
    fun issueForApprovedQrLogin(userId: Long, client: ClientContext): AuthResult {
        val user = users.findById(userId) ?: throw NotFound("Account")
        val (result, sessionId) = issueSession(user, client, familyId = null, SessionOrigin.QR_CODE)
        audit.record(userId, AuditAction.QR_LOGIN_APPROVED, sessionId = sessionId)
        return result
    }

    /** @return the tokens, plus the id of the session row that backs them. */
    private fun issueSession(
        user: User,
        client: ClientContext,
        familyId: Long?,
        origin: SessionOrigin = SessionOrigin.PASSWORD,
    ): Pair<AuthResult, Long> {
        val sessionId = snowflake.next()
        val refreshToken = crypto.randomToken()

        sessions.create(
            id = sessionId,
            userId = user.id,
            refreshTokenHash = crypto.sha256(refreshToken),
            familyId = familyId ?: sessionId,   // a fresh login starts its own family
            deviceId = client.deviceId,
            userAgent = client.userAgent,
            ip = client.ip,
            platform = client.platform,
            origin = origin,
            expiresAt = Instant.now().plus(props.auth.refreshTokenTtl),
        )

        val result = AuthResult(
            accessToken = accessTokens.issue(user.id, sessionId),
            refreshToken = refreshToken,
            expiresInSeconds = accessTokens.ttl.seconds.toInt(),
            user = user,
        )
        return result to sessionId
    }

    private fun validateUsername(username: String) {
        if (!USERNAME_RE.matches(username)) {
            throw InvalidInput(
                "Usernames are 2-32 characters: letters, numbers, underscore and period only."
            )
        }
    }

    private fun validateEmail(email: String) {
        if (!EMAIL_RE.matches(email)) throw InvalidInput("That doesn't look like an email address.")
    }

    private fun validatePassword(password: String) {
        // Length beats composition rules. NIST dropped character-class requirements precisely
        // because they push people toward P@ssw0rd1 and nothing else.
        if (password.length < 10) throw InvalidInput("Passwords need at least 10 characters.")
        if (password.length > 512) throw InvalidInput("That password is too long.")
    }

    private companion object {
        val LOG = org.slf4j.LoggerFactory.getLogger(AuthService::class.java)!!
        val USERNAME_RE = Regex("^[A-Za-z0-9_.]{2,32}$")
        val EMAIL_RE = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
