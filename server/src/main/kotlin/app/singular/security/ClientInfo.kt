package app.singular.security

import org.springframework.http.HttpHeaders
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** What the transport can tell us about the caller. Recorded, never trusted for identity. */
data class ClientInfo(val ip: String?, val userAgent: String?)

const val CLIENT_INFO_KEY = "singular.clientInfo"

object ClientInfoResolver {

    /**
     * Behind a reverse proxy the socket's peer address is the proxy, so the real client IP has
     * to come from a forwarded header.
     *
     * Those headers are client-settable and therefore forgeable — only trust them when your
     * proxy is configured to overwrite rather than append. If it isn't, everything recorded in
     * `connection_sessions.ip` is attacker-controlled.
     */
    fun resolve(headers: HttpHeaders): ClientInfo {
        val forwarded = headers.getFirst("X-Forwarded-For")
            ?.substringBefore(',')      // leftmost entry is the original client
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        val ip = forwarded
            ?: headers.getFirst("X-Real-IP")?.trim()?.takeIf { it.isNotEmpty() }
            ?: remoteAddr()

        return ClientInfo(
            ip = ip,
            userAgent = headers.getFirst(HttpHeaders.USER_AGENT)?.take(512),
        )
    }

    /** Direct connections only; absent on the WebSocket path, which is fine — sockets record
     *  their IP at connection_init and the HTTP login that preceded them already did. */
    private fun remoteAddr(): String? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
            ?.request
            ?.remoteAddr
            ?.takeIf { it.isNotEmpty() }
}
