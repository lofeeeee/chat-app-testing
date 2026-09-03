package app.singular.security

import app.singular.core.NotAuthenticated
import graphql.GraphQLContext
import org.springframework.graphql.server.WebGraphQlInterceptor
import org.springframework.graphql.server.WebGraphQlRequest
import org.springframework.graphql.server.WebGraphQlResponse
import org.springframework.graphql.server.WebSocketGraphQlInterceptor
import org.springframework.graphql.server.WebSocketGraphQlRequest
import org.springframework.graphql.server.WebSocketSessionInfo
import org.springframework.stereotype.Component
import reactor.core.publisher.Mono

/** The authenticated caller. Never trust a user id that didn't come from here. */
data class Principal(val userId: Long, val sessionId: Long)

const val PRINCIPAL_KEY = "singular.principal"

/** Throws rather than returning null — every authenticated resolver wants the same failure. */
fun GraphQLContext.requirePrincipal(): Principal =
    get<Principal>(PRINCIPAL_KEY) ?: throw NotAuthenticated()

fun GraphQLContext.principalOrNull(): Principal? = get<Principal>(PRINCIPAL_KEY)

/**
 * Resolves a bearer token into a [Principal] for both transports.
 *
 * HTTP carries the token in the `Authorization` header on every request.
 *
 * WebSocket carries it **once**, in the `connection_init` payload, because browsers cannot set
 * headers on a WebSocket handshake. The principal is then bound to the socket for its lifetime
 * and re-checked only on reconnect.
 *
 * That asymmetry is precisely why access tokens are short-lived: a socket authenticated at
 * connect time would otherwise outlive a revocation for as long as it stays open. Anything that
 * must take effect immediately — a ban, a forced logout — has to revoke the refresh session
 * *and* close the affected sockets.
 */
@Component
class AuthInterceptor(
    private val accessTokens: AccessTokens,
) : WebGraphQlInterceptor, WebSocketGraphQlInterceptor {

    override fun intercept(
        request: WebGraphQlRequest,
        chain: WebGraphQlInterceptor.Chain,
    ): Mono<WebGraphQlResponse> {
        val principal = when (request) {
            // Established at connection_init; inherited by every operation on this socket.
            is WebSocketGraphQlRequest ->
                request.sessionInfo.attributes[PRINCIPAL_KEY] as? Principal

            else ->
                request.headers.getFirst("Authorization")?.let(::parseBearer)
        }

        val clientInfo = ClientInfoResolver.resolve(request.headers)

        request.configureExecutionInput { _, builder ->
            builder.graphQLContext { ctx ->
                ctx.put(CLIENT_INFO_KEY, clientInfo)
                if (principal != null) ctx.put(PRINCIPAL_KEY, principal)
            }.build()
        }

        // Absent principal is not an error here — register and login are public. Resolvers that
        // need a caller ask for one via requirePrincipal().
        return chain.next(request)
    }

    override fun handleConnectionInitialization(
        sessionInfo: WebSocketSessionInfo,
        connectionInitPayload: MutableMap<String, Any>,
    ): Mono<Any> {
        val header = connectionInitPayload["authorization"] as? String
            ?: connectionInitPayload["Authorization"] as? String

        // Anonymous sockets are allowed, deliberately and narrowly: a device waiting on a QR
        // sign-in has no token yet, and `loginRequestUpdated` is the one subscription that must
        // work before authentication. It authenticates itself with the request's poll secret
        // instead. Every other subscription resolver calls requirePrincipal() and fails here,
        // so the socket being open buys an attacker nothing but a rate-limited connection.
        header?.let(::parseBearer)?.let { sessionInfo.attributes[PRINCIPAL_KEY] = it }

        return Mono.empty()
    }

    private fun parseBearer(header: String): Principal? {
        val token = header.removePrefix("Bearer ").trim().ifEmpty { return null }
        val claims = accessTokens.verify(token) ?: return null
        return Principal(claims.userId, claims.sessionId)
    }
}
