package app.singular.message

import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Someone started typing. Carries no message content — there isn't any yet. */
data class TypingEvent(
    val channelId: Long,
    val userId: Long,
    val at: Instant = Instant.now(),
)

/**
 * Typing indicators.
 *
 * Deliberately fire-and-forget and never persisted. A typing notice is worthless a few seconds
 * after it was sent, so there is nothing to store and nothing to replay — a client that misses
 * one simply doesn't show the bubble, which is the correct behaviour anyway.
 *
 * There is intentionally no "stopped typing" event. Clients expire the indicator on a timer,
 * because the common way typing really ends is the tab being closed or the network dropping —
 * neither of which sends anything. Relying on a stop event leaves indicators stuck on forever;
 * every chat app that has shipped that bug learned it the same way.
 *
 * Node-local like [MessageEvents], and swapped for Valkey pub/sub in the same phase.
 */
@Component
class TypingEvents {

    private val sinks = ConcurrentHashMap<Long, Sinks.Many<TypingEvent>>()

    fun publish(event: TypingEvent) {
        sinks[event.channelId]?.tryEmitNext(event)
    }

    fun subscribe(channelId: Long): Flux<TypingEvent> {
        val sink = sinks.computeIfAbsent(channelId) {
            // Dropping a typing notice under load is completely fine — it expires in seconds
            // regardless. Never apply backpressure for this.
            Sinks.many().multicast().directBestEffort()
        }

        return sink.asFlux().doFinally {
            sinks.computeIfPresent(channelId) { _, existing ->
                if (existing.currentSubscriberCount() == 0) null else existing
            }
        }
    }
}
