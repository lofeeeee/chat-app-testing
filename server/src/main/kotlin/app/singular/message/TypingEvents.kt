package app.singular.message

import app.singular.event.FanoutBus
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.time.Instant

/** Someone started typing. Carries no message content — there isn't any yet. */
data class TypingEvent(
    val channelId: Long,
    val userId: Long,
    val at: Instant = Instant.now(),
)

/**
 * Typing indicators.
 *
 * Deliberately fire-and-forget and never persisted. A typing notice is worthless a few
 * seconds after it was sent, so there is nothing to store and nothing to replay — a client
 * that misses one simply doesn't show the bubble, which is the correct behaviour anyway.
 *
 * There is intentionally no "stopped typing" event. Clients expire the indicator on a timer,
 * because the common way typing really ends is the tab being closed or the network dropping —
 * neither of which sends anything. Relying on a stop event leaves indicators stuck on forever;
 * every chat app that has shipped that bug learned it the same way.
 *
 * Cross-node via Valkey pub/sub (`singular:typing:<channelId>`); dropped notices are fine at
 * every layer of that path too.
 */
@Component
class TypingEvents(private val bus: FanoutBus) {

    fun publish(event: TypingEvent) {
        bus.publish("typing", event.channelId.toString(), event)
    }

    fun subscribe(channelId: Long): Flux<TypingEvent> =
        bus.subscribe("typing", channelId.toString())
}
