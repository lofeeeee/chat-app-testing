package app.singular.event

import com.fasterxml.jackson.databind.ObjectMapper
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

/**
 * Cross-node pub/sub, and the one place that knows Valkey is involved.
 *
 * The deal this class implements: a node publishes an event to a Valkey channel, and EVERY
 * node — including the publisher — receives it back and feeds it to its own local
 * subscribers. Local sinks are fed only from the subscription, never from the publish call.
 * That one round trip buys the absence of an entire bug class: "did I already deliver this
 * locally?" is no longer a question anyone has to answer, because there is exactly one
 * delivery path.
 *
 * ## Channel names
 *
 * `singular:<type>:<key>` — e.g. `singular:message:1234` for channel 1234's messages. A
 * node subscribes to a channel key only while it has at least one local subscriber for it
 * (ref-counted below), so a node's Valkey traffic scales with what its own clients are
 * actually watching, not with everything happening everywhere.
 *
 * ## Envelope
 *
 * The payload travels as JSON via the app's existing Jackson mapper. The envelope carries the
 * type and key so one subscription stream can be demultiplexed and filtered; the payload
 * itself is left untouched as a JSON tree, so this class never needs to know a payload type.
 */
@Component
class FanoutBus(
    private val pubSub: StatefulRedisPubSubConnection<String, String>,
    private val mapper: ObjectMapper,
) {

    /** Serializes [publish] payloads and the [Envelope] they travel in. */
    private val envelopeWriter = mapper.writerFor(Envelope::class.java)

    /**
     * One entry per subscribed channel key: the sink local subscribers read from, and how
     * many local subscribers there are. The moment the count returns to zero the entry is
     * dropped and Valkey unsubscribes — mirrors the doFinally cleanup the in-memory
     * implementations used to do themselves.
     */
    private val channels = ConcurrentHashMap<String, ChannelEntry>()

    private class ChannelEntry(val sink: Sinks.Many<String>) {
        val refCount = java.util.concurrent.atomic.AtomicInteger(0)
    }

    init {
        pubSub.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) {
                channels[channel]?.sink?.tryEmitNext(message)
            }
        })
    }

    /** Publishes [payload] to every node — including this one. Fire-and-forget, best effort. */
    fun <T> publish(type: String, key: String, payload: T, payloadType: Class<T>) {
        val channel = channelName(type, key)
        val envelope = Envelope(type = type, key = key, payload = mapper.valueToTree(payload))
        runCatching {
            // The async API is deliberate: publishing must never block the caller's (virtual)
            // thread on a network round trip. If the connection is down the event is lost —
            // for everything on this bus that is acceptable; none of it is the source of truth.
            pubSub.async().publish(channel, envelopeWriter.writeValueAsString(envelope))
        }.onFailure { LOG.warn("Fanout publish to {} failed: {}", channel, it.message) }
    }

    /**
     * Kotlin-reified convenience for [publish].
     *
     * `final` is required, not stylistic: `kotlin("plugin.spring")` opens `@Component` classes
     * so Spring can proxy them, and an `inline` member of an open class cannot be dispatched
     * virtually. Nothing overrides these, so making them final costs no flexibility.
     */
    final inline fun <reified T> publish(type: String, key: String, payload: T) =
        publish(type, key, payload, T::class.java)

    /**
     * A stream of [type] events for [key], as decoded [T]s. First local subscriber subscribes
     * at Valkey; last unsubscribe releases the subscription.
     */
    fun <T> subscribe(type: String, key: String, clazz: Class<T>): Flux<T> {
        val channel = channelName(type, key)
        val entry = channels.computeIfAbsent(channel) { ChannelEntry(Sinks.many().multicast().directBestEffort()) }
        entry.refCount.incrementAndGet()
        pubSub.async().subscribe(channel)

        return entry.sink.asFlux()
            .map { raw -> mapper.readValue(raw, Envelope::class.java) }
            .filter { it.type == type && it.key == key }
            .map { mapper.treeToValue(it.payload, clazz) }
            .doFinally {
                if (entry.refCount.decrementAndGet() <= 0) {
                    channels.remove(channel, entry)
                    runCatching { pubSub.async().unsubscribe(channel) }
                }
            }
    }

    /** Kotlin-reified convenience for [subscribe]. Final for the reason given on [publish]. */
    final inline fun <reified T> subscribe(type: String, key: String): Flux<T> =
        subscribe(type, key, T::class.java)

    /** How many channels this node currently watches. Exposed for metrics. */
    fun watchedChannelCount(): Int = channels.size

    private fun channelName(type: String, key: String) = "singular:$type:$key"

    /**
     * The wire format. `payload` is a JsonNode so the mapper handles arbitrary payload types
     * without this class knowing any of them.
     */
    data class Envelope(
        val type: String,
        val key: String,
        val payload: com.fasterxml.jackson.databind.JsonNode,
    )

    private companion object {
        val LOG = LoggerFactory.getLogger(FanoutBus::class.java)!!
    }
}
