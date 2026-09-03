package app.singular.config

import graphql.GraphQLContext
import graphql.execution.CoercedVariables
import graphql.language.StringValue
import graphql.language.Value
import graphql.schema.Coercing
import graphql.schema.CoercingParseValueException
import graphql.schema.CoercingSerializeException
import graphql.schema.GraphQLScalarType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.graphql.execution.RuntimeWiringConfigurer
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale

@Configuration
class GraphQlConfig {

    @Bean
    fun scalarConfigurer(): RuntimeWiringConfigurer = RuntimeWiringConfigurer { wiring ->
        wiring.scalar(SNOWFLAKE).scalar(DATE_TIME)
    }

    companion object {
        /**
         * Snowflakes cross the wire as strings, always.
         *
         * A 64-bit id exceeds JavaScript's 2^53 safe integer range, and JSON.parse rounds
         * silently rather than failing — so an unquoted id arrives subtly wrong and every
         * lookup for it misses. Discord quotes every id for exactly this reason.
         */
        val SNOWFLAKE: GraphQLScalarType = GraphQLScalarType.newScalar()
            .name("Snowflake")
            .description("A 64-bit id, serialised as a decimal string.")
            .coercing(object : Coercing<Long, String> {

                override fun serialize(result: Any, ctx: GraphQLContext, locale: Locale): String =
                    when (result) {
                        is Long -> result.toString()
                        is String -> result
                        is Number -> result.toLong().toString()
                        else -> throw CoercingSerializeException(
                            "Expected a Snowflake, got ${result::class.simpleName}"
                        )
                    }

                override fun parseValue(input: Any, ctx: GraphQLContext, locale: Locale): Long =
                    when (input) {
                        is Long -> input
                        is Int -> input.toLong()
                        is String -> input.toLongOrNull()
                            ?: throw CoercingParseValueException("Not a valid Snowflake: $input")
                        else -> throw CoercingParseValueException(
                            "Not a valid Snowflake: ${input::class.simpleName}"
                        )
                    }

                override fun parseLiteral(
                    input: Value<*>,
                    vars: CoercedVariables,
                    ctx: GraphQLContext,
                    locale: Locale,
                ): Long = when (input) {
                    is StringValue -> input.value.toLongOrNull()
                        ?: throw CoercingParseValueException("Not a valid Snowflake: ${input.value}")
                    is graphql.language.IntValue -> input.value.toLong()
                    else -> throw CoercingParseValueException("Snowflake must be a string literal")
                }
            })
            .build()

        val DATE_TIME: GraphQLScalarType = GraphQLScalarType.newScalar()
            .name("DateTime")
            .description("An RFC 3339 / ISO 8601 instant in UTC.")
            .coercing(object : Coercing<Instant, String> {

                override fun serialize(result: Any, ctx: GraphQLContext, locale: Locale): String =
                    when (result) {
                        is Instant -> result.toString()
                        is java.sql.Timestamp -> result.toInstant().toString()
                        is java.time.OffsetDateTime -> result.toInstant().toString()
                        is String -> result
                        else -> throw CoercingSerializeException(
                            "Expected an Instant, got ${result::class.simpleName}"
                        )
                    }

                override fun parseValue(input: Any, ctx: GraphQLContext, locale: Locale): Instant =
                    try {
                        Instant.parse(input.toString())
                    } catch (e: DateTimeParseException) {
                        throw CoercingParseValueException("Not a valid DateTime: $input", e)
                    }

                override fun parseLiteral(
                    input: Value<*>,
                    vars: CoercedVariables,
                    ctx: GraphQLContext,
                    locale: Locale,
                ): Instant {
                    val raw = (input as? StringValue)?.value
                        ?: throw CoercingParseValueException("DateTime must be a string literal")
                    return try {
                        Instant.parse(raw)
                    } catch (e: DateTimeParseException) {
                        throw CoercingParseValueException("Not a valid DateTime: $raw", e)
                    }
                }
            })
            .build()
    }
}
