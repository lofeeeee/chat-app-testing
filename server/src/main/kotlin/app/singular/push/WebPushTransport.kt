package app.singular.push

import app.singular.config.SingularProperties
import com.fasterxml.jackson.databind.ObjectMapper
import nl.martijndwars.webpush.Notification
import nl.martijndwars.webpush.PushService as WebPushClient
import nl.martijndwars.webpush.Subscription
import org.slf4j.LoggerFactory

/**
 * Web Push (RFC 8030) with VAPID (RFC 8292) and aes128gcm payload encryption (RFC 8291).
 *
 * The one transport where a library is genuinely non-negotiable: RFC 8291's content-encryption
 * key derivation (ECDH + HKDF with an info-context built from both endpoints' public keys) is
 * exactly the kind of protocol detail a hand-rolled version gets subtly wrong, and a wrong
 * derivation means notifications that arrive encrypted with keys nobody can derive — silently
 * dead, never an error. `nl.martijndwars:web-push` is a small BouncyCastle-based library that
 * exists for precisely this method set.
 *
 * (Aliased in — the library's `PushService` collides with this package's own `PushService`,
 * which is the notification decision-maker, not a provider client.)
 *
 * The token for Web Push is the subscription JSON the browser handed the client:
 * `{"endpoint":"https://push.service/…","keys":{"p256dh":"…","auth":"…"}}`.
 */
class WebPushTransport(
    private val config: SingularProperties.Push.WebPush,
    private val mapper: ObjectMapper,
) : PushTransport {

    override val platform = PushPlatform.WEB_PUSH

    // One client per process; it holds the VAPID key pair and an HTTP client.
    private val client = WebPushClient(
        config.vapidPublicKey,
        config.vapidPrivateKey,
        config.subject,
    )

    override fun send(token: String, message: PushMessage): Boolean {
        val subscription = parseSubscription(token) ?: run {
            LOG.warn("Web Push: token is not a usable subscription JSON — marking dead")
            return false
        }

        val payload = mapper.writeValueAsString(
            mapOf(
                "title" to message.title,
                "body" to message.body,
                "channelId" to (message.channelId?.toString() ?: ""),
                "messageId" to (message.messageId?.toString() ?: ""),
            )
        )

        val notification = Notification(subscription, payload)
        client.send(notification).use { response ->
            return when (response.statusLine.statusCode) {
                201 -> true
                // 404/410: subscription expired or revoked — dead.
                404, 410 -> false
                429, 500, 502, 503 -> {
                    LOG.warn("Web Push transient failure {} — will retry", response.statusLine.statusCode)
                    throw PushRetryable("status ${response.statusLine.statusCode}")
                }
                else -> {
                    LOG.warn(
                        "Web Push rejected a send: {} {}",
                        response.statusLine.statusCode,
                        response.statusLine.reasonPhrase,
                    )
                    false
                }
            }
        }
    }

    private fun parseSubscription(token: String): Subscription? = runCatching {
        val tree = mapper.readTree(token)
        val endpoint = tree.get("endpoint")?.asText().orEmpty()
        val keys = tree.get("keys")
        val p256dh = keys?.get("p256dh")?.asText().orEmpty()
        val auth = keys?.get("auth")?.asText().orEmpty()
        if (endpoint.isBlank() || p256dh.isBlank() || auth.isBlank()) null
        else Subscription(endpoint, Subscription.Keys(p256dh, auth))
    }.getOrNull()

    private companion object {
        val LOG = LoggerFactory.getLogger(WebPushTransport::class.java)!!
    }
}
