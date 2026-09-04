package app.singular.api

import app.singular.auth.AuthResult
import app.singular.auth.AuthService
import app.singular.domain.ClientContext
import app.singular.ratelimit.RateLimiter
import app.singular.security.CLIENT_INFO_KEY
import app.singular.security.ClientInfo
import graphql.GraphQLContext
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.stereotype.Controller
import java.util.UUID

data class RegisterInput(
    val username: String,
    val email: String,
    val password: String,
    val displayName: String? = null,
    val deviceId: String? = null,
)

data class LoginInput(
    val email: String,
    val password: String,
    val deviceId: String? = null,
)

@Controller
class AuthController(
    private val auth: AuthService,
    private val rateLimiter: RateLimiter,
) {

    /**
     * Registration is unauthenticated, so the client IP is the only identity available to key
     * the limit on. The policy is the tightest in the app: registration from one IP at more
     * than a trickle is account farming, not sign-ups.
     */
    @MutationMapping
    fun register(@Argument input: RegisterInput, ctx: GraphQLContext): AuthResult {
        rateLimiter.acquireOrThrow("register", clientKey(ctx))
        return auth.register(
            username = input.username,
            email = input.email,
            password = input.password,
            displayName = input.displayName,
            client = clientContext(ctx, input.deviceId),
        )
    }

    /**
     * Limits credential stuffing: without this, an attacker can try thousands of
     * email/password pairs per minute. Keyed by IP, not account, because the stuffing attack
     * rotates the account name and keeps the IP — limiting per-account would miss it entirely.
     */
    @MutationMapping
    fun login(@Argument input: LoginInput, ctx: GraphQLContext): AuthResult {
        rateLimiter.acquireOrThrow("login", clientKey(ctx))
        return auth.login(
            email = input.email,
            password = input.password,
            client = clientContext(ctx, input.deviceId),
        )
    }

    /** Refresh presents a long-lived secret; throttling it blunts brute-force of a leaked token. */
    @MutationMapping
    fun refresh(@Argument refreshToken: String, ctx: GraphQLContext): AuthResult {
        rateLimiter.acquireOrThrow("token-refresh", clientKey(ctx))
        return auth.refresh(refreshToken, clientContext(ctx, deviceId = null))
    }

    @MutationMapping
    fun logout(@Argument refreshToken: String): Boolean = auth.logout(refreshToken)

    /**
     * A client that doesn't send a device id gets a fresh random one each time, which means its
     * sessions can't be grouped by device in the security settings screen. That's a degraded
     * experience, not a failure — so we don't reject the request over it.
     */
    private fun clientContext(ctx: GraphQLContext, deviceId: String?): ClientContext {
        val info = ctx.get<ClientInfo>(CLIENT_INFO_KEY) ?: ClientInfo(null, null)
        return ClientContext(
            ip = info.ip,
            userAgent = info.userAgent,
            deviceId = deviceId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: UUID.randomUUID(),
        )
    }

    /**
     * The bucket key for anonymous endpoints. Falls back to "unknown" when no IP survived
     * resolution — all such callers then share one bucket, which is over-throttling rather
     * than under-throttling, and the direction of that failure is deliberate.
     */
    private fun clientKey(ctx: GraphQLContext): String =
        ctx.get<ClientInfo>(CLIENT_INFO_KEY)?.ip?.takeIf { it.isNotBlank() } ?: "unknown"
}
