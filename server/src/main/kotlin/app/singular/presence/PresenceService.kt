package app.singular.presence

import app.singular.domain.PresenceStatus
import app.singular.social.SocialRepository
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** What other people see. Never carries INVISIBLE — that is reported as OFFLINE. */
data class Presence(
    val userId: Long,
    val status: PresenceStatus,
    val customText: String? = null,
    val customEmoji: String? = null,
)

/**
 * Effective presence.
 *
 * Two things get conflated here constantly, so they are kept apart:
 *
 *   * **Desired status** — what the user picked. Durable, in `users.desired_status`, and it has
 *     to survive a reconnect: someone who sets DND and closes the app is still DND tomorrow.
 *   * **Effective status** — what everyone else sees. Derived from live connections, disposable,
 *     and held here in memory.
 *
 * The rule joining them: no live connection means OFFLINE regardless of what was chosen, and
 * INVISIBLE is reported as OFFLINE to everyone but the user themselves. Invisible that leaks as
 * its own state is not invisible.
 *
 * In-memory and node-local, exactly like [app.singular.message.MessageEvents]. Presence is the
 * single clearest case for Valkey — it changes several times a minute per user and is worthless
 * after a restart — and this class is the seam where that swap happens.
 */
@Service
class PresenceService(private val social: SocialRepository) {

    /** userId -> last heartbeat. A user with no entry is offline, full stop. */
    private val heartbeats = ConcurrentHashMap<Long, Instant>()

    private val events: Sinks.Many<Presence> = Sinks.many().multicast().directBestEffort()

    fun heartbeat(userId: Long) {
        val wasOffline = heartbeats.put(userId, Instant.now()) == null
        if (wasOffline) publish(userId)
    }

    fun disconnect(userId: Long) {
        if (heartbeats.remove(userId) != null) publish(userId)
    }

    /** As seen by [viewerId]. Pass the user's own id to see your own INVISIBLE honestly. */
    fun presenceOf(userId: Long, viewerId: Long?): Presence {
        val desired = social.desiredStatus(userId)
        return Presence(
            userId = userId,
            status = effective(userId, desired.status, viewerId),
            customText = desired.customText,
            customEmoji = desired.customEmoji,
        )
    }

    /** Batched for member lists, so a 50-person channel is one query rather than 50. */
    fun presenceFor(userIds: Collection<Long>, viewerId: Long?): Map<Long, Presence> {
        val desired = social.desiredStatusFor(userIds)
        return userIds.associateWith { id ->
            val d = desired[id]
            Presence(
                userId = id,
                status = effective(id, d?.status ?: PresenceStatus.ONLINE, viewerId),
                customText = d?.customText,
                customEmoji = d?.customEmoji,
            )
        }
    }

    fun setStatus(userId: Long, status: PresenceStatus) {
        social.setDesiredStatus(userId, status)
        publish(userId)
    }

    fun setCustomStatus(userId: Long, text: String?, emoji: String?, expiresAt: Instant?) {
        social.setCustomStatus(userId, text, emoji, expiresAt)
        publish(userId)
    }

    fun subscribe(): Flux<Presence> = events.asFlux()

    private fun effective(userId: Long, desired: PresenceStatus, viewerId: Long?): PresenceStatus {
        val live = heartbeats[userId]?.let { it.isAfter(Instant.now().minus(TIMEOUT)) } == true
        return when {
            // You always see your own real state, including INVISIBLE — otherwise the status
            // picker can't show you what you actually selected.
            userId == viewerId -> desired
            !live -> PresenceStatus.OFFLINE
            desired == PresenceStatus.INVISIBLE -> PresenceStatus.OFFLINE
            else -> desired
        }
    }

    private fun publish(userId: Long) {
        // Published without a viewer, so INVISIBLE is already collapsed to OFFLINE. Emitting the
        // raw desired status here would leak it to every subscriber.
        val desired = social.desiredStatus(userId)
        events.tryEmitNext(
            Presence(
                userId = userId,
                status = effective(userId, desired.status, viewerId = null),
                customText = desired.customText,
                customEmoji = desired.customEmoji,
            )
        )
    }

    private companion object {
        /**
         * How long a connection stays "live" without a heartbeat.
         *
         * Generous on purpose: a client that briefly loses its socket should not flicker to
         * offline and back in everyone else's sidebar.
         */
        val TIMEOUT: Duration = Duration.ofSeconds(60)
    }
}
