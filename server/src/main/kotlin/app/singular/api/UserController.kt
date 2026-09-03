package app.singular.api

import app.singular.core.InvalidInput
import app.singular.domain.User
import app.singular.security.principalOrNull
import app.singular.security.requirePrincipal
import app.singular.user.UserRepository
import graphql.GraphQLContext
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class UserController(private val users: UserRepository) {

    /** Null rather than an error when signed out — the client uses this to decide what to show. */
    @QueryMapping
    fun me(ctx: GraphQLContext): User? =
        ctx.principalOrNull()?.let { users.findById(it.userId) }

    @QueryMapping
    fun user(@Argument id: Long, ctx: GraphQLContext): User? {
        ctx.requirePrincipal()
        return users.findById(id)
    }

    @QueryMapping
    fun userByHandle(
        @Argument username: String,
        @Argument discriminator: Int,
        ctx: GraphQLContext,
    ): User? {
        ctx.requirePrincipal()
        if (discriminator !in 1..9999) {
            throw InvalidInput("Discriminators run from 0001 to 9999.")
        }
        return users.findByHandle(username, discriminator.toShort())
    }
}
