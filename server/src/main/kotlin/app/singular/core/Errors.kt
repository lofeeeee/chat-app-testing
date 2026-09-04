package app.singular.core

import graphql.ErrorClassification
import graphql.ErrorType
import graphql.GraphQLError
import graphql.schema.DataFetchingEnvironment
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter
import org.springframework.graphql.execution.ErrorType as SpringErrorType
import org.springframework.stereotype.Component

/**
 * Domain failures the client is allowed to see and act on.
 *
 * Anything not in this hierarchy is a bug, and [DomainExceptionResolver] deliberately lets it
 * fall through as a generic INTERNAL_ERROR — leaking a stack trace or a SQL constraint name to
 * an unauthenticated caller is how schema details escape.
 */
sealed class DomainException(
    message: String,
    val code: String,
    val classification: ErrorClassification,
) : RuntimeException(message)

class NotAuthenticated : DomainException(
    "Sign in to do that.", "UNAUTHENTICATED", SpringErrorType.UNAUTHORIZED
)

/**
 * The credentials presented were wrong.
 *
 * Distinct from [NotAuthenticated], which means "you presented no credentials at all" — showing
 * someone who just typed a password "Sign in to do that" is nonsense, and that is exactly the
 * bug this class exists to prevent.
 *
 * The wording deliberately does not say which half was wrong, and is identical whether or not
 * the account exists. Saying "no account with that email" turns the login form into an
 * account-enumeration oracle, which is the same reason [AuthService] burns a dummy hash on a
 * missed lookup.
 */
class InvalidCredentials : DomainException(
    "That email or password isn't right.", "INVALID_CREDENTIALS", SpringErrorType.UNAUTHORIZED
)

class Forbidden(what: String) : DomainException(
    "You don't have access to $what.", "FORBIDDEN", SpringErrorType.FORBIDDEN
)

class NotFound(what: String) : DomainException(
    "$what not found.", "NOT_FOUND", SpringErrorType.NOT_FOUND
)

class InvalidInput(message: String) : DomainException(
    message, "BAD_INPUT", SpringErrorType.BAD_REQUEST
)

/**
 * The caller is going too fast. Not a credentials problem and not a permissions problem —
 * deliberately a separate class so clients can render "try again in N seconds" instead of
 * surfacing a permission error to a user whose only mistake was a double-click.
 *
 * HTTP-wise this is 429 territory; GraphQL has no native 429, so it travels as FORBIDDEN with
 * the `RATE_LIMITED` code and a `retryAfterSeconds` extension — the two things every client
 * needs to back off correctly.
 */
class RateLimited(val retryAfterSeconds: Long) : DomainException(
    "You're going too fast. Try again in ${retryAfterSeconds}s.",
    "RATE_LIMITED",
    SpringErrorType.FORBIDDEN,
)

class Conflict(message: String) : DomainException(
    message, "CONFLICT", ErrorType.ValidationError
)

/** Every 9,999 discriminators for this username are taken — the wall Discord eventually hit. */
class NameExhausted(username: String) : DomainException(
    "The name \"$username\" is full. Try a different one.",
    "NAME_EXHAUSTED",
    ErrorType.ValidationError,
)

@Component
class DomainExceptionResolver : DataFetcherExceptionResolverAdapter() {

    override fun resolveToSingleError(
        ex: Throwable,
        env: DataFetchingEnvironment,
    ): GraphQLError? = when (ex) {
        is RateLimited -> graphql.GraphqlErrorBuilder.newError(env)
            .message(ex.message)
            .errorType(ex.classification)
            .extensions(
                mapOf(
                    "code" to ex.code,
                    // The one number a backing-off client needs; kept out of the prose so it's
                    // machine-readable without parsing English.
                    "retryAfterSeconds" to ex.retryAfterSeconds,
                )
            )
            .build()

        is DomainException -> graphql.GraphqlErrorBuilder.newError(env)
            .message(ex.message)
            .errorType(ex.classification)
            .extensions(mapOf("code" to ex.code))
            .build()

        // Not ours — let Spring produce a generic INTERNAL_ERROR and log the detail server-side.
        else -> null
    }
}
