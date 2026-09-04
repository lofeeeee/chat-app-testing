package app.singular.auth

import app.singular.audit.AuditLog
import app.singular.core.Forbidden
import app.singular.core.NotAuthenticated
import app.singular.core.NotFound
import app.singular.core.Snowflake
import app.singular.domain.AuditAction
import app.singular.domain.ClientContext
import app.singular.domain.LoginRequest
import app.singular.domain.LoginRequestStatus
import app.singular.domain.User
import app.singular.event.FanoutBus
import app.singular.security.ClientInfo
import app.singular.security.Crypto
import app.singular.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** What the requesting device is told, as the sign-in progresses. */
data class LoginRequestEvent(
    val status: LoginRequestStatus,
    val approvedBy: User? = null,
    /** Present exactly once, on approval, and only on the poll-secret-authenticated channel. */
    val auth: AuthResult? = null,
)

/** Returned once, at creation. The poll secret never appears again and never goes in the QR. */
data class NewLoginRequest(
    val request: LoginRequest,
    val qrToken: String,
    val pollSecret: String,
)

/** What the scanning phone is shown before it decides. This screen is the security control. */
data class ScannedLoginRequest(
    val id: Long,
    val ipAddress: String?,
    val platform: String?,
    val userAgent: String?,
    val requestedAt: Instant,
)

/**
 * QR sign-in, WhatsApp Web / Discord Remote Auth style.
 *
 * ## The two-secret split
 *
 * Creation hands the device a **qrToken** (public, goes in the QR, rotates every 20s) and a
 * **pollSecret** (private, never displayed, never rotates). If the QR token were the only
 * credential, rotating it would achieve nothing — the device would have to keep honouring the
 * old value to stay subscribed. Splitting them lets the public half rotate freely.
 *
 * ## What rotation actually buys
 *
 * It bounds replay of a QR captured off a screen: a screen share, a photo, a shoulder surfer,
 * a leaked screenshot. Twenty seconds makes a captured code near-useless.
 *
 * It does **not** stop QRLJacking — the attack where someone shows a victim *their own* login
 * QR and gets them to scan it, capturing the victim's account onto the attacker's machine.
 * Nothing about token lifetime helps there. The only real defence is [claim] returning the
 * requesting device's IP, platform and user agent so the approval screen can name what is
 * actually being admitted, and requiring a separate explicit [approve]. That step is not
 * decoration and must not be auto-confirmed.
 */
@Service
class QrLoginService(
    private val requests: LoginRequestRepository,
    private val users: UserRepository,
    private val authService: AuthService,
    private val crypto: Crypto,
    private val snowflake: Snowflake,
    private val audit: AuditLog,
    private val bus: FanoutBus,
) {

    /**
     * Per-request replay sinks. Approval may happen on a DIFFERENT node than the one holding
     * the requesting device's websocket, so the event itself travels via [bus] (Valkey), and
     * this map only holds the node-local replay buffer each device reads from. The poll-secret
     * check stays local and unchanged — it gates the subscription, not the fanout.
     */
    private val sinks = ConcurrentHashMap<Long, Sinks.Many<LoginRequestEvent>>()

    @Transactional
    fun create(client: ClientInfo, deviceId: UUID, platform: String?): NewLoginRequest {
        val id = snowflake.next()
        val qrToken = crypto.randomToken()
        val pollSecret = crypto.randomToken()
        val now = Instant.now()

        requests.create(
            id = id,
            tokenHash = crypto.sha256(qrToken),
            pollSecretHash = crypto.sha256(pollSecret),
            ip = client.ip,
            userAgent = client.userAgent,
            platform = platform,
            deviceId = deviceId,
            tokenExpiresAt = now.plus(TOKEN_TTL),
            expiresAt = now.plus(REQUEST_TTL),
        )

        val request = requests.findById(id) ?: error("Login request $id vanished after insert")
        return NewLoginRequest(request, qrToken, pollSecret)
    }

    /**
     * Issues the next QR token.
     *
     * Refuses once the request has been scanned: the phone is holding that token until the user
     * approves or denies, and rotating out from under it would break the approval screen for no
     * visible reason.
     */
    @Transactional
    fun rotate(id: Long, pollSecret: String): Pair<LoginRequest, String> {
        requirePollSecret(id, pollSecret)

        val current = requests.findById(id) ?: throw NotFound("Sign-in request")
        if (current.status != LoginRequestStatus.PENDING) {
            // Not an error — the caller just gets the unchanged request and stops rotating.
            return current to ""
        }

        val fresh = crypto.randomToken()
        val expiry = Instant.now().plus(TOKEN_TTL)
        if (!requests.rotateToken(id, crypto.sha256(fresh), expiry)) {
            throw NotFound("Sign-in request")
        }
        return (requests.findById(id) ?: throw NotFound("Sign-in request")) to fresh
    }

    /**
     * Called by an already-authenticated phone that scanned the code.
     *
     * Returns what the approval screen needs to show. Deliberately does **not** sign anyone in.
     */
    @Transactional
    fun claim(qrToken: String, userId: Long): ScannedLoginRequest {
        val request = requests.findByTokenHash(crypto.sha256(qrToken))
            ?: throw NotFound("Sign-in request")

        val now = Instant.now()
        if (request.expiresAt.isBefore(now) || request.tokenExpiresAt.isBefore(now)) {
            throw NotFound("That code has expired — refresh it and scan again")
        }
        // Guarded UPDATE, so two phones racing on the same captured QR: exactly one wins.
        if (!requests.markScanned(request.id, userId)) {
            throw NotFound("That code has already been used")
        }

        audit.record(userId, AuditAction.QR_LOGIN_SCANNED, targetId = request.id)
        publish(request.id, LoginRequestEvent(LoginRequestStatus.SCANNED))

        return ScannedLoginRequest(
            id = request.id,
            ipAddress = request.requestIp,
            platform = request.requestPlatform,
            userAgent = request.requestUserAgent,
            requestedAt = request.createdAt,
        )
    }

    @Transactional
    fun approve(id: Long, userId: Long): Boolean {
        if (!requests.resolve(id, userId, LoginRequestStatus.APPROVED)) {
            throw Forbidden("that sign-in request")
        }

        val request = requests.findById(id) ?: throw NotFound("Sign-in request")
        val approver = users.findById(userId) ?: throw NotAuthenticated()

        // The session belongs to the DEVICE THAT ASKED, not to the phone that approved. Using
        // the phone's context here would file the new session under the wrong device in the
        // user's sessions list — and make a stolen-laptop session look like their phone.
        val auth = authService.issueForApprovedQrLogin(
            userId = userId,
            client = ClientContext(
                ip = request.requestIp,
                userAgent = request.requestUserAgent,
                deviceId = request.requestDeviceId ?: UUID.randomUUID(),
                platform = request.requestPlatform,
            ),
        )

        // Tokens travel only on the poll-secret-authenticated channel — never back to the
        // scanner, which is why claim() returns device details and nothing else.
        publish(id, LoginRequestEvent(LoginRequestStatus.APPROVED, approver, auth))
        requests.markConsumed(id)
        return true
    }

    @Transactional
    fun deny(id: Long, userId: Long): Boolean {
        if (!requests.resolve(id, userId, LoginRequestStatus.DENIED)) {
            throw Forbidden("that sign-in request")
        }
        audit.record(userId, AuditAction.QR_LOGIN_DENIED, targetId = id)
        publish(id, LoginRequestEvent(LoginRequestStatus.DENIED))
        return true
    }

    /**
     * The requesting device's update channel, authenticated by its poll secret.
     *
     * `replay().latest()` rather than a plain multicast: a device whose socket blipped between
     * the scan and the approval would otherwise miss the one event carrying its tokens and hang
     * on a QR that will never resolve. Re-subscribing still costs the poll secret, so replay
     * doesn't widen who can read it.
     */
    fun subscribe(id: Long, pollSecret: String): Flux<LoginRequestEvent> {
        requirePollSecret(id, pollSecret)
        return sinkFor(id).asFlux()
    }

    /**
     * Publishes to every node via Valkey. Each node feeds its own local replay sink from the
     * subscription, so the device's gets the event regardless of which node handled the
     * approving phone.
     */
    private fun publish(id: Long, event: LoginRequestEvent) {
        bus.publish("qr", id.toString(), event)
    }

    /** The Valkey → local-sink wiring for each tracked request, so it can be torn down again. */
    private val busWires = ConcurrentHashMap<Long, reactor.core.Disposable>()

    private fun sinkFor(id: Long): Sinks.Many<LoginRequestEvent> =
        sinks.computeIfAbsent(id) {
            // Wire the local replay sink to this node's Valkey subscription for the request.
            // FanoutBus ref-counts the underlying channel subscription; this wire is undone
            // in releaseSink so a finished request costs nothing on any node.
            busWires.computeIfAbsent(id) {
                bus.subscribe<LoginRequestEvent>("qr", id.toString())
                    .subscribe { incoming -> sinks[id]?.tryEmitNext(incoming) }
            }
            Sinks.many().replay().latest()
        }

    /** Constant-time comparison: a byte-by-byte early exit leaks the secret one byte at a time. */
    private fun requirePollSecret(id: Long, pollSecret: String) {
        val stored = requests.findPollSecretHash(id) ?: throw NotFound("Sign-in request")
        if (!Crypto.constantTimeEquals(stored, crypto.sha256(pollSecret))) {
            throw NotAuthenticated()
        }
    }

    /** Drops sinks for requests that can no longer change. Called by the reaper. */
    fun releaseSink(id: Long) {
        // Dispose the wire first so a late Valkey event finds no sink to fill; the opposite
        // ordering can resurrect an entry in `sinks` via tryEmitNext.
        busWires.remove(id)?.dispose()
        sinks.remove(id)?.tryEmitComplete()
    }

    fun trackedRequestCount(): Int = sinks.size

    companion object {
        /**
         * The QR token's lifetime. Five seconds longer than the client's rotation interval, so a
         * scan that lands at 19.9s still resolves instead of failing on a race the user can
         * neither see nor understand.
         */
        val TOKEN_TTL: Duration = Duration.ofSeconds(25)

        /** How long the client waits before swapping the code. This is the "every 20s" knob. */
        const val ROTATE_AFTER_SECONDS = 20

        /** How long the whole sign-in attempt stays open before the user has to start over. */
        val REQUEST_TTL: Duration = Duration.ofMinutes(3)
    }
}
