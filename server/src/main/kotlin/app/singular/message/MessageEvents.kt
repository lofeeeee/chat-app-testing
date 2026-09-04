package app.singular.message

import app.singular.domain.Message
import app.singular.event.FanoutBus
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux

/**
 * Fanout for `messageCreated`.
 *
 * The publish goes to Valkey (`singular:message:<channelId>`) and comes back to every node —
 * this one included — which then feeds its own local subscribers. Local sinks are only ever
 * fed from the subscription, so there is exactly one delivery path and no way for a node to
 * deliver a message twice, or zero times because it decided the publish covered it.
 *
 * A node only subscribes to a channel's Valkey topic while at least one of its own clients
 * is subscribed to that channel, so a two-node deployment doesn't double everyone's inbound
 * event traffic — each node sees only what its own clients are watching.
 *
 * Backpressure note, carried over from the in-memory version: a client too slow to keep up
 * drops events rather than stalling every other subscriber on the channel (directBestEffort
 * inside FanoutBus). The client recovers by refetching on reconnect.
 */
@Component
class MessageEvents(private val bus: FanoutBus) {

    fun publish(message: Message) {
        bus.publish("message", message.channelId.toString(), message)
    }

    fun subscribe(channelId: Long): Flux<Message> =
        bus.subscribe("message", channelId.toString())
}
