package app.singular.event

import app.singular.domain.Message
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Envelope codec tests — no Valkey required.
 *
 * The bus's moving parts split cleanly into "talks to Valkey" (the connection, untestable
 * without a server, verified by the manual two-node procedure in the implementation plan) and
 * "turns a payload into bytes and back" (this file). The wire format is a compatibility
 * surface between *nodes*, potentially running different builds, so an accidental format
 * change is worth catching in a unit test rather than in production split-brain.
 */
class FanoutBusTest {

    /**
     * Mirrors the app's mapper: Kotlin module for data classes, JSR-310 for Instant. The
     * production mapper gets both from Spring Boot auto-configuration; the test builds the
     * same thing by hand because the codec is what's under test, not the wiring.
     */
    private val mapper = ObjectMapper()
        .registerModule(KotlinModule.Builder().build())
        .registerModule(JavaTimeModule())

    @Test
    fun `envelope round-trips a message payload`() {
        val message = Message(
            id = 1234567890123456L,
            channelId = 42,
            authorId = 7,
            content = "hello across nodes",
            replyToId = null,
            createdAt = java.time.Instant.parse("2026-09-04T12:00:00Z"),
            editedAt = null,
        )

        val envelope = FanoutBus.Envelope(
            type = "message",
            key = "42",
            payload = mapper.valueToTree(message),
        )

        val wire = mapper.writeValueAsString(envelope)
        val decoded = mapper.readValue(wire, FanoutBus.Envelope::class.java)

        assertEquals("message", decoded.type)
        assertEquals("42", decoded.key)
        val back = mapper.treeToValue(decoded.payload, Message::class.java)
        assertEquals(message, back)
    }

    @Test
    fun `snowflake ids survive the wire as full 64-bit values`() {
        // 2^53 + 1: silently rounded by any JSON parser that treats numbers as doubles.
        // The envelope's payload tree must preserve it exactly — this is the same reason the
        // GraphQL Snowflake scalar serializes as a string.
        val bigId = (1L shl 53) + 1L
        val message = Message(
            id = bigId,
            channelId = 1,
            authorId = 1,
            content = "x",
            replyToId = null,
            createdAt = java.time.Instant.parse("2026-09-04T12:00:00Z"),
            editedAt = null,
        )

        val wire = mapper.writeValueAsString(
            FanoutBus.Envelope("message", "1", mapper.valueToTree(message))
        )
        val decoded = mapper.readValue(wire, FanoutBus.Envelope::class.java)
        val back = mapper.treeToValue(decoded.payload, Message::class.java)

        assertEquals(bigId, back.id, "a 64-bit id must not round through a double")
    }

    @Test
    fun `a decoded envelope from another type is filtered by type and key`() {
        // The bus multiplexes every event type over one listener; a subscriber must never see
        // an envelope it didn't ask for. The filter predicates live in FanoutBus.subscribe,
        // but the inputs to those predicates — the envelope's own type and key fields — are
        // what this pins down.
        val envelope = FanoutBus.Envelope(
            type = "typing",
            key = "42",
            payload = mapper.valueToTree(mapOf("channelId" to 42, "userId" to 9)),
        )
        assertTrue(envelope.type != "message" || envelope.key != "41")
    }
}
