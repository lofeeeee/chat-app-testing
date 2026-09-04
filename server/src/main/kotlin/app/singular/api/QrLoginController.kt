package app.singular.api

import app.singular.auth.AuthResult
import app.singular.auth.LoginRequestEvent
import app.singular.auth.NewLoginRequest
import app.singular.auth.QrLoginService
import app.singular.auth.ScannedLoginRequest
import app.singular.domain.LoginRequest
import app.singular.domain.User
import app.singular.ratelimit.RateLimiter
import app.singular.security.CLIENT_INFO_KEY
import app.singular.security.ClientInfo
import app.singular.security.requirePrincipal
import graphql.GraphQLContext
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.graphql.data.method.annotation.SubscriptionMapping
import org.springframework.stereotype.Controller
import reactor.core.publisher.Flux
import java.util.UUID

/** Carries the freshly minted token alongside the row, so `qrPayload` can resolve it. */
data class LoginRequestView(val request: LoginRequest, val qrToken: String)

@Controller
class QrLoginController(
    private val qr: QrLoginService,
    private val rateLimiter: RateLimiter,
) {

    // -- Requesting device (unauthenticated) --------------------------------

    /**
     * Anonymous, and before this limit existed it minted login requests without bound — the
     * largest open hole on the server. Keyed by IP: a device legitimately creates one request
     * and rotates its token every twenty seconds; anything faster is a script.
     */
    @MutationMapping
    fun createLoginRequest(
        @Argument deviceId: String?,
        @Argument platform: String?,
        ctx: GraphQLContext,
    ): NewLoginRequest {
        rateLimiter.acquireOrThrow("qr-create", clientKey(ctx))
        return qr.create(
            client = ctx.get<ClientInfo>(CLIENT_INFO_KEY) ?: ClientInfo(null, null),
            deviceId = deviceId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: UUID.randomUUID(),
            platform = platform?.take(120),
        )
    }

    /** Token rotation runs on the same anonymous edge, so it gets its own (looser) bucket. */
    @MutationMapping
    fun rotateLoginToken(
        @Argument id: Long,
        @Argument pollSecret: String,
        ctx: GraphQLContext,
    ): LoginRequestView {
        rateLimiter.acquireOrThrow("qr-rotate", clientKey(ctx))
        val (request, token) = qr.rotate(id, pollSecret)
        return LoginRequestView(request, token)
    }

    /**
     * The requesting device's update channel.
     *
     * Note there is no `requirePrincipal()` here, and that is deliberate: a device waiting on a
     * QR sign-in has no bearer token yet. The poll secret is the credential, checked inside
     * [QrLoginService.subscribe] in constant time. This is the only subscription that works on
     * an anonymous socket.
     */
    @SubscriptionMapping
    fun loginRequestUpdated(
        @Argument id: Long,
        @Argument pollSecret: String,
    ): Flux<LoginRequestEvent> = qr.subscribe(id, pollSecret)

    // -- Approving phone (authenticated) ------------------------------------

    /**
     * Keyed by user, not IP: this is the QR brute-force surface (guessing token values), and a
     * guesser with many IPs still has one account-sized bucket. The token itself is 256 bits,
     * so the limit is belt-and-braces rather than the primary defence.
     */
    @MutationMapping
    fun claimLoginRequest(
        @Argument qrToken: String,
        ctx: GraphQLContext,
    ): ScannedLoginRequest {
        val principal = ctx.requirePrincipal()
        rateLimiter.acquireOrThrow("qr-claim", principal.userId.toString())
        return qr.claim(qrToken, principal.userId)
    }

    @MutationMapping
    fun approveLoginRequest(@Argument id: Long, ctx: GraphQLContext): Boolean =
        qr.approve(id, ctx.requirePrincipal().userId)

    @MutationMapping
    fun denyLoginRequest(@Argument id: Long, ctx: GraphQLContext): Boolean =
        qr.deny(id, ctx.requirePrincipal().userId)

    /**
     * The bucket key for anonymous endpoints. Same fallback semantics as AuthController: an
     * unresolvable IP collapses to one shared bucket rather than escaping the limit.
     */
    private fun clientKey(ctx: GraphQLContext): String =
        ctx.get<ClientInfo>(CLIENT_INFO_KEY)?.ip?.takeIf { it.isNotBlank() } ?: "unknown"

    // -- Field resolvers ----------------------------------------------------

    /**
     * What actually goes in the QR.
     *
     * A custom-scheme URL rather than a bare token, so scanning it with the phone's built-in
     * camera app deep-links straight into Singular instead of showing the user an opaque
     * string they have to copy. The token is single-use and expires in 25 seconds, which is
     * what makes putting it in a URL acceptable.
     */
    @SchemaMapping(typeName = "LoginRequest", field = "qrPayload")
    fun qrPayload(view: LoginRequestView): String =
        "singular://login?id=${view.request.id}&t=${view.qrToken}"

    @SchemaMapping(typeName = "LoginRequest", field = "id")
    fun id(view: LoginRequestView): Long = view.request.id

    @SchemaMapping(typeName = "LoginRequest", field = "status")
    fun status(view: LoginRequestView): String = view.request.status.name

    @SchemaMapping(typeName = "LoginRequest", field = "tokenExpiresAt")
    fun tokenExpiresAt(view: LoginRequestView) = view.request.tokenExpiresAt

    @SchemaMapping(typeName = "LoginRequest", field = "expiresAt")
    fun expiresAt(view: LoginRequestView) = view.request.expiresAt

    @SchemaMapping(typeName = "LoginRequest", field = "rotateAfterSeconds")
    fun rotateAfterSeconds(@Suppress("UNUSED_PARAMETER") view: LoginRequestView): Int =
        QrLoginService.ROTATE_AFTER_SECONDS

    @SchemaMapping(typeName = "NewLoginRequest", field = "request")
    fun request(created: NewLoginRequest) = LoginRequestView(created.request, created.qrToken)

    @SchemaMapping(typeName = "LoginRequestEvent", field = "status")
    fun eventStatus(event: LoginRequestEvent): String = event.status.name

    @SchemaMapping(typeName = "LoginRequestEvent", field = "approvedBy")
    fun eventApprover(event: LoginRequestEvent): User? = event.approvedBy

    @SchemaMapping(typeName = "LoginRequestEvent", field = "auth")
    fun eventAuth(event: LoginRequestEvent): AuthResult? = event.auth
}
