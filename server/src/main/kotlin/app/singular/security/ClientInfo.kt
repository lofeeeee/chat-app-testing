package app.singular.security

import org.springframework.http.HttpHeaders
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/** What the transport can tell us about the caller. Recorded, never trusted for identity. */
data class ClientInfo(val ip: String?, val userAgent: String?)

const val CLIENT_INFO_KEY = "singular.clientInfo"

object ClientInfoResolver {

    /**
     * Whether forwarded headers may be trusted, set once at startup from
     * `singular.trust-proxy`. Defaults to false — the fail-safe direction.
     *
     * Behind a reverse proxy the socket's peer address is the proxy, so the real client IP has
     * to come from a forwarded header. But those headers are client-settable and therefore
     * forgeable: only trust them when your proxy is configured to overwrite rather than append.
     *
     * With this flag off (the default), an exposed deployment ignores `X-Forwarded-For`
     * entirely — every request appears to come from the proxy's address. That collapses
     * IP-keyed rate limits, so it's still wrong to leave it off *behind a proxy*; the point
     * of the flag is that trusting forwarded headers is now an explicit operator decision
     * rather than a silent default an attacker gets to exploit directly. If it isn't set and
     * the server isn't behind a proxy, a spoofed `X-Forwarded-For` no longer mints a fresh
     * rate-limit identity per request.
     */
    @Volatile
    var trustProxy: Boolean = false

    fun resolve(headers: HttpHeaders): ClientInfo {
        val forwarded = if (trustProxy) {
            headers.getFirst("X-Forwarded-For")
                ?.substringBefore(',')      // leftmost entry is the original client
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }

        val ip = forwarded
            ?: (if (trustProxy) headers.getFirst("X-Real-IP")?.trim()?.takeIf { it.isNotEmpty() } else null)
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
