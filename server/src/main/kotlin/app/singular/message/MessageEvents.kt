package app.singular.message

import app.singular.domain.Message
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

/**
 * In-process fanout for `messageCreated`.
 *
 * **This is node-local and that is a phase-1 limitation, not a design.** Two backend instances
 * would each only notify their own connected clients. Phase 2 replaces [publish] with a Valkey
 * `PUBLISH` and has every node subscribe to the channel keys its own clients care about — the
 * shape below is deliberately the same, so that swap touches this class only.
 *
 * Retrofitting cross-node fanout later is painful precisely because it leaks into every
 * resolver if the seam isn't isolated up front. It is.
 */
@Component
class MessageEvents {

    private val sinks = ConcurrentHashMap<Long, Sinks.Many<Message>>()

    fun publish(message: Message) {
        // No subscribers for this channel on this node is the common case, not an error.
        sinks[message.channelId]?.tryEmitNext(message)
    }

    fun subscribe(channelId: Long): Flux<Message> {
        val sink = sinks.computeIfAbsent(channelId) {
            // directBestEffort: a client too slow to keep up drops events rather than applying
            // backpressure that would stall every other subscriber on the channel. The client
            // recovers by refetching on reconnect — which the resume protocol makes cheap.
            Sinks.many().multicast().directBestEffort()
        }

        return sink.asFlux().doFinally {
            // Last subscriber left — drop the sink so channel churn doesn't leak memory.
            // computeIfPresent keeps the check and removal atomic against a concurrent
            // subscribe() that just found this same sink.
            sinks.computeIfPresent(channelId) { _, existing ->
                if (existing.currentSubscriberCount() == 0) null else existing
            }
        }
    }

    /** Exposed for metrics: how many channels currently have a live subscriber on this node. */
    fun activeChannelCount(): Int = sinks.size
}
