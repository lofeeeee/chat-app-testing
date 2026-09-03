package app.singular.api

import app.singular.auth.AuthResult
import app.singular.auth.LoginRequestEvent
import app.singular.auth.NewLoginRequest
import app.singular.auth.QrLoginService
import app.singular.auth.ScannedLoginRequest
import app.singular.domain.LoginRequest
import app.singular.domain.User
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
class QrLoginController(private val qr: QrLoginService) {

    // -- Requesting device (unauthenticated) --------------------------------

    @MutationMapping
    fun createLoginRequest(
        @Argument deviceId: String?,
        @Argument platform: String?,
        ctx: GraphQLContext,
    ): NewLoginRequest = qr.create(
        client = ctx.get<ClientInfo>(CLIENT_INFO_KEY) ?: ClientInfo(null, null),
        deviceId = deviceId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: UUID.randomUUID(),
        platform = platform?.take(120),
    )

    @MutationMapping
    fun rotateLoginToken(
        @Argument id: Long,
        @Argument pollSecret: String,
    ): LoginRequestView {
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

    @MutationMapping
    fun claimLoginRequest(
        @Argument qrToken: String,
        ctx: GraphQLContext,
    ): ScannedLoginRequest = qr.claim(qrToken, ctx.requirePrincipal().userId)

    @MutationMapping
    fun approveLoginRequest(@Argument id: Long, ctx: GraphQLContext): Boolean =
        qr.approve(id, ctx.requirePrincipal().userId)

    @MutationMapping
    fun denyLoginRequest(@Argument id: Long, ctx: GraphQLContext): Boolean =
        qr.deny(id, ctx.requirePrincipal().userId)

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
