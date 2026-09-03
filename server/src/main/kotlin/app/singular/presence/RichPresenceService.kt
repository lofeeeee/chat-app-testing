package app.singular.presence

import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * "Playing Factorio", "Listening to Teardrop". Feature 17.
 *
 * Types mirror Discord's so third-party integrations that already speak that vocabulary need
 * no translation layer.
 */
data class RichPresence(
    val userId: Long,
    val activityType: String,        // PLAYING, LISTENING, WATCHING, STREAMING, COMPETING
    val name: String,
    val details: String? = null,
    val state: String? = null,
    val largeImageKey: String? = null,
    val startedAt: Instant = Instant.now(),
    val endsAt: Instant? = null,
)

/**
 * Rich presence.
 *
 * **Never persisted, by design.** What someone is playing right now is meaningless five
 * minutes later, and writing it to Postgres would mean a row update every time a track
 * changes — for data that is wrong the moment it goes stale. It lives in memory, disappears on
 * disconnect, and disappears again on restart, which is exactly the correct behaviour.
 *
 * This is the same seam as [PresenceService] and [app.singular.message.MessageEvents]: node
 * local now, one Valkey `PUBLISH` away from working across a cluster.
 *
 * On desktop the feed comes from a local IPC socket — a named pipe on Windows, a Unix socket
 * elsewhere — that third-party apps connect to, which is how Discord's integrations work and
 * why so many games already support it.
 */
@Service
class RichPresenceService {

    private val current = ConcurrentHashMap<Long, RichPresence>()
    private val events: Sinks.Many<RichPresence> = Sinks.many().multicast().directBestEffort()

    fun set(presence: RichPresence) {
        current[presence.userId] = presence
        events.tryEmitNext(presence)
    }

    fun clear(userId: Long) {
        // Cleared state still has to be broadcast, or everyone else keeps showing the activity
        // the user just stopped. An empty name is the signal.
        if (current.remove(userId) != null) {
            events.tryEmitNext(RichPresence(userId, activityType = "NONE", name = ""))
        }
    }

    /**
     * What to show for one person.
     *
     * Entries past their own `endsAt` are dropped on read rather than swept: a track that
     * finished is stale immediately, and a sweeper would always be a few seconds behind.
     */
    fun of(userId: Long): RichPresence? {
        val presence = current[userId] ?: return null
        val ended = presence.endsAt?.isBefore(Instant.now()) == true
        val stale = presence.startedAt.isBefore(Instant.now().minus(MAX_AGE))
        if (ended || stale) {
            current.remove(userId)
            return null
        }
        return presence
    }

    fun forUsers(userIds: Collection<Long>): Map<Long, RichPresence> =
        userIds.mapNotNull { id -> of(id)?.let { id to it } }.toMap()

    fun subscribe(): Flux<RichPresence> = events.asFlux()

    private companion object {
        /**
         * Nothing survives longer than this without a refresh.
         *
         * A client that crashes mid-game never sends a clear, so without a ceiling its user
         * would show as "Playing" indefinitely — the rich-presence equivalent of a typing
         * indicator stuck on forever.
         */
        val MAX_AGE: Duration = Duration.ofHours(6)
    }
}
