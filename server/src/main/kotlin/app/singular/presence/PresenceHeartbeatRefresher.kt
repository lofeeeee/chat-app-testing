package app.singular.presence

import app.singular.security.WebSocketSessionRegistry
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * Refreshes presence heartbeats for every user this node still serves over a live socket.
 *
 * This is the server-side half of folding the client's 25s heartbeat POST into the socket:
 * the connect edge of a WebSocket records the first beat (see `AuthInterceptor`), and this
 * pass keeps it alive for as long as the socket is. The client no longer posts anything on a
 * timer — one fewer HTTP request per interval per online user, and presence stops being a
 * claim the client makes ("I POSTed recently") and becomes a fact the server observes
 * ("this user holds a live authenticated socket").
 *
 * ## Why a fixed-delay pass and not per-socket timers
 *
 * One query over the registry's key set beats one scheduled task per socket: a node with ten
 * thousand sockets then runs one map scan every 15s, not ten thousand timers, and a socket
 * that closes between passes simply drops out of the next scan's set — no cancellation
 * bookkeeping to get wrong.
 *
 * ## Truthfulness
 *
 * If a user's socket dies silently (NAT timeout, laptop lid), the beat stops refreshing, the
 * Valkey key lapses after `HEARTBEAT_TTL`, and they read offline — same failure behaviour as
 * the client-POST model, but without the window where a client that can't receive anything
 * keeps claiming to be online by POSTing. A user with no socket is offline in the only sense
 * that matters here: nothing can reach them.
 */
@Component
class PresenceHeartbeatRefresher(
    private val presence: PresenceService,
    private val registry: WebSocketSessionRegistry,
) {
    /**
     * 15s against a 60s TTL: a delayed pass (GC pause, slow Valkey) is tolerated up to 45s
     * before anyone reads offline. Empty registries return immediately, so an idle node pays
     * nothing but a map scan.
     */
    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    fun refresh() {
        val users = registry.userIds()
        if (users.isEmpty()) return
        users.forEach { presence.heartbeat(it) }
    }
}
