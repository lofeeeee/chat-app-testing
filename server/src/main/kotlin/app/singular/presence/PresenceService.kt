package app.singular.presence

import app.singular.config.SingularProperties
import app.singular.domain.PresenceStatus
import app.singular.event.FanoutBus
import app.singular.social.SocialRepository
import io.lettuce.core.SetArgs
import io.lettuce.core.api.StatefulRedisConnection
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import java.time.Duration
import java.time.Instant

/** What other people see. Never carries INVISIBLE — that is reported as OFFLINE. */
data class Presence(
    val userId: Long,
    val status: PresenceStatus,
    val customText: String? = null,
    val customEmoji: String? = null,
)

/**
 * Effective presence, now shared across nodes.
 *
 * Two things get conflated here constantly, so they are kept apart:
 *
 *   * **Desired status** — what the user picked. Durable, in `users.desired_status`, and it has
 *     to survive a reconnect: someone who sets DND and closes the app is still DND tomorrow.
 *   * **Effective status** — what everyone else sees. Derived from live connections, disposable,
 *     and shared via Valkey.
 *
 * The rule joining them: no live connection anywhere means OFFLINE regardless of what was
 * chosen, and INVISIBLE is reported as OFFLINE to everyone but the user themselves. Invisible
 * that leaks as its own state is not invisible.
 *
 * ## How "live" works across nodes
 *
 * Every node records, per user it holds connections for: a heartbeat key
 * `presence:hb:<userId>:<nodeId>` with a 60s TTL (refreshed while the node serves them), and
 * membership in the set `presence:nodes:<userId>` (so the expiry question is "does this
 * member's heartbeat key still exist", not a scan). A user is live when ANY member of that
 * set has a live heartbeat — which is what makes "connected to two nodes, one drops" not
 * flicker the user offline.
 *
 * The set is cleaned lazily on read: a member whose heartbeat expired is removed there and
 * then, which also covers a node crashing without the courtesy of a goodbye (its keys simply
 * lapse; its set entries are pruned the next time anyone asks about that user).
 *
 * ## Events carry a prompt, not a verdict
 *
 * A presence-change event tells subscribers "user X changed — recheck". The subscriber
 * recomputes from the shared state rather than trusting the event's own status field, so a
 * user on two nodes can't publish two contradictory verdicts — last write doesn't win, the
 * shared state does. INVISIBLE never enters an event at all; the publish path collapses it to
 * OFFLINE the same way `effective` does, so a leak in any consumer is structurally impossible.
 */
@Service
class PresenceService(
    private val social: SocialRepository,
    private val bus: FanoutBus,
    private val redis: StatefulRedisConnection<String, String>,
    private val props: SingularProperties,
) {

    private val sync = redis.sync()

    fun heartbeat(userId: Long) {
        sync.set(hbKey(userId, props.nodeId), "1", SetArgs().px(HEARTBEAT_TTL.toMillis()))
        sync.sadd(nodesKey(userId), props.nodeId.toString())
    }

    fun disconnect(userId: Long) {
        // Remove only OUR node's membership. The user may still be connected elsewhere, and
        // this is the whole reason membership is a set rather than one key.
        sync.del(hbKey(userId, props.nodeId))
        sync.srem(nodesKey(userId), props.nodeId.toString())
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
        publishChange(userId)
    }

    fun setCustomStatus(userId: Long, text: String?, emoji: String?, expiresAt: Instant?) {
        social.setCustomStatus(userId, text, emoji, expiresAt)
        publishChange(userId)
    }

    /**
     * The stream other clients subscribe to. The event itself is only a prompt to recheck (see
     * class doc), so the resolved Presence is computed here, on arrival, rather than being
     * carried inside the event: that keeps two nodes connected to the same user from ever
     * publishing contradictory verdicts, and keeps INVISIBLE out of the wire format entirely.
     *
     * Viewer is null on this path by construction — the prompt is not person-specific. An
     * INVISIBLE user therefore shows as OFFLINE here, which is the point of invisible.
     */
    fun subscribe(): Flux<Presence> =
        bus.subscribe<PresenceChange>("presence", "all")
            .map { change -> presenceOf(change.userId, viewerId = null) }

    /** A nudge to recheck — see the class doc for why the event is not the verdict. */
    data class PresenceChange(val userId: Long)

    private fun effective(userId: Long, desired: PresenceStatus, viewerId: Long?): PresenceStatus {
        val live = isLiveAnywhere(userId)
        return when {
            // You always see your own real state, including INVISIBLE — otherwise the status
            // picker can't show you what you actually selected.
            userId == viewerId -> desired
            !live -> PresenceStatus.OFFLINE
            desired == PresenceStatus.INVISIBLE -> PresenceStatus.OFFLINE
            else -> desired
        }
    }

    private fun isLiveAnywhere(userId: Long): Boolean {
        val nodes = sync.smembers(nodesKey(userId))
        if (nodes.isEmpty()) return false

        var live = false
        nodes.forEach { nodeId ->
            if (sync.exists(hbKey(userId, nodeId.toLongOrNull() ?: -1)) == 1L) {
                live = true
            } else {
                // Dead member: heartbeat lapsed (node crash or unannounced disconnect).
                // Prune now so the set can't grow without bound across weeks of churn.
                sync.srem(nodesKey(userId), nodeId)
            }
        }
        return live
    }

    private fun publishChange(userId: Long) {
        bus.publish("presence", "all", PresenceChange(userId))
    }

    private fun hbKey(userId: Long, nodeId: Long) = "presence:hb:$userId:$nodeId"
    private fun nodesKey(userId: Long) = "presence:nodes:$userId"

    private companion object {
        /**
         * How long a node's heartbeat for a user lasts without a refresh. Generous on purpose:
         * a client that briefly loses its socket should not flicker the user to offline and
         * back in everyone else's sidebar.
         */
        val HEARTBEAT_TTL: Duration = Duration.ofSeconds(60)
    }
}
