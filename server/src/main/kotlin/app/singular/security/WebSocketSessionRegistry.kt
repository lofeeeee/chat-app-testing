package app.singular.security

import org.springframework.graphql.server.WebSocketSessionInfo
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Live WebSocket sessions, keyed by the user that authenticated them.
 *
 * The missing half of revocation: `AuthInterceptor` binds a Principal to a socket for the
 * socket's lifetime, so a revoked session keeps a working subscription until the access
 * token happens to expire. Closing the socket is the only immediate enforcement.
 *
 * ## Why session-info only, and how closing actually works
 *
 * `WebSocketSessionInfo` is a read-only view (id/attributes/uri/headers) with no close
 * method — the closable `org.springframework.web.socket.WebSocketSession` is private behind
 * Spring GraphQL's `WebMvcSessionInfo` wrapper. What the view *does* expose is
 * `getAttributes()`, which is the same mutable map the underlying session carries. So the
 * handshake captures the raw session into that map under [RAW_SESSION_KEY], and
 * [closeForUser] reads it back and closes. The capture is done by decorating the
 * auto-configured `GraphQlWebSocketHandler` (see `WebSocketCaptureConfig`).
 *
 * ## Semantics
 *
 *  - Keyed by *user id*: family/session ids rotate under refresh and a socket outlives the
 *    access token that opened it — the user is the stable key a socket can be found by.
 *  - One user, many sockets: each device runs several subscriptions.
 *  - Entries are removed on close, via [onDisconnected], so the map tracks live sockets.
 *  - Closing is best-effort and never throws into the revocation path: failing to kill one
 *    socket must not roll back the revocation that killed everything else.
 */
@Component
class WebSocketSessionRegistry {

    private val byUser = ConcurrentHashMap<Long, MutableSet<WebSocketSessionInfo>>()

    /** Registers a socket authenticated as [userId]. Called from connection init. */
    fun onConnected(userId: Long, session: WebSocketSessionInfo) {
        byUser.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(session)
    }

    /** Drops a socket from the registry. Called when the socket ends, however it ended. */
    fun onDisconnected(session: WebSocketSessionInfo) {
        val principal = session.attributes[PRINCIPAL_KEY] as? Principal
        if (principal != null) {
            // Targeted removal: the socket's own user entry, cleaned up when it empties so
            // the map tracks *live* sockets, not every user who ever connected.
            byUser[principal.userId]?.let { set ->
                set.remove(session)
                if (set.isEmpty()) byUser.remove(principal.userId, set)
            }
            return
        }
        // Defensive sweep for sockets that somehow registered without a principal —
        // onConnected only ever registers those that have one, but a scan that finds
        // nothing costs nothing.
        byUser.values.forEach { it.remove(session) }
    }

    /** Snapshot of the users with at least one live authenticated socket on this node. */
    fun userIds(): Set<Long> = byUser.keys.toSet()

    /** Whether this node still holds any live socket authenticated as [userId]. */
    fun hasSocketsFor(userId: Long): Boolean = !byUser[userId].isNullOrEmpty()

    /**
     * Closes every live socket the user authenticated with, and clears their registry entry.
     *
     * @return how many sockets were targeted, for the audit log.
     */
    fun closeForUser(userId: Long): Int {
        val sockets = byUser.remove(userId) ?: return 0
        sockets.forEach { session ->
            runCatching {
                (session.attributes[RAW_SESSION_KEY] as? org.springframework.web.socket.WebSocketSession)
                    ?.close(org.springframework.web.socket.CloseStatus(4401, "Session revoked"))
            }
        }
        return sockets.size
    }

    companion object {
        /** The handshake puts the closable session here; see the class doc. */
        const val RAW_SESSION_KEY = "singular.rawWsSession"
    }
}
