package app.singular.push

import app.singular.core.Snowflake
import app.singular.social.SocialRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

enum class PushPlatform(val code: Short) {
    FCM(0), APNS(1), WEB_PUSH(2);

    companion object {
        fun ofCode(code: Short) = entries.firstOrNull { it.code == code } ?: FCM
        fun byName(name: String) = entries.firstOrNull { it.name == name }
    }
}

data class PushToken(
    val id: Long,
    val userId: Long,
    val platform: PushPlatform,
    val token: String,
)

data class PushMessage(
    val userId: Long,
    val title: String,
    val body: String,
    val channelId: Long?,
    val messageId: Long?,
)

@Repository
class PushTokenRepository(private val jdbc: JdbcClient) {

    fun register(id: Long, userId: Long, platform: PushPlatform, token: String, deviceId: UUID?) {
        jdbc.sql(
            """
            INSERT INTO push_tokens (id, user_id, platform, token, device_id)
            VALUES (:id, :u, :p, :t, :d)
            ON CONFLICT (token) DO UPDATE SET
                user_id      = EXCLUDED.user_id,
                last_used_at = now(),
                invalid_at   = NULL
            """
        )
            .param("id", id).param("u", userId).param("p", platform.code.toInt())
            .param("t", token).param("d", deviceId)
            .update()
    }

    fun unregister(token: String): Boolean = jdbc
        .sql("DELETE FROM push_tokens WHERE token = :t")
        .param("t", token)
        .update() == 1

    fun tokensFor(userId: Long): List<PushToken> = jdbc
        .sql(
            """
            SELECT id, user_id, platform, token FROM push_tokens
            WHERE user_id = :u AND invalid_at IS NULL
            """
        )
        .param("u", userId)
        .query { rs, _ ->
            PushToken(
                rs.getLong("id"), rs.getLong("user_id"),
                PushPlatform.ofCode(rs.getShort("platform")), rs.getString("token"),
            )
        }
        .list()

    fun markInvalid(token: String) {
        // Marked rather than deleted: tokens that start failing often recover, and deleting
        // immediately throws away the signal that a device is flapping.
        jdbc.sql("UPDATE push_tokens SET invalid_at = now() WHERE token = :t")
            .param("t", token).update()
    }
}

/**
 * How a notification actually reaches a device.
 *
 * One interface, so FCM, APNs and Web Push are three implementations rather than three
 * branches scattered through the notification path.
 */
interface PushTransport {
    val platform: PushPlatform
    /** @return false when the provider says the token is dead, so it can be marked invalid. */
    fun send(token: String, message: PushMessage): Boolean
}

/**
 * Stand-in until credentials exist.
 *
 * Registration, the mute/DND filter and the fanout are all real and testable without Apple or
 * Google; only the final hop isn't. Logging instead of sending keeps the whole path exercised,
 * so wiring the real transports later is a matter of adding two classes rather than
 * discovering the surrounding logic was never run.
 *
 * No longer `@Service` — [PushTransportConfig] decides between this and the real transports
 * based on credentials, and registering both would leave an ambiguous transport list.
 */
class LoggingPushTransport : PushTransport {
    override val platform = PushPlatform.FCM
    override fun send(token: String, message: PushMessage): Boolean {
        LOG.info(
            "[push:not-configured] -> user={} token={}… \"{}: {}\"",
            message.userId, token.take(12), message.title, message.body,
        )
        return true
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(LoggingPushTransport::class.java)!!
    }
}

/**
 * Decides whether to notify, then fans out.
 *
 * **[shouldNotify] is the single place blocks, mutes and Do Not Disturb are honoured.** Those
 * three features are only real if something checks them before dispatch, and every chat app
 * that has shipped a "muted channel still buzzed me" bug got there by checking in two places.
 */
@Service
class PushService(
    private val tokens: PushTokenRepository,
    private val social: SocialRepository,
    private val presence: app.singular.presence.PresenceService,
    private val transports: List<PushTransport>,
    private val snowflake: Snowflake,
) {

    fun register(userId: Long, platform: PushPlatform, token: String, deviceId: UUID?) {
        tokens.register(snowflake.next(), userId, platform, token.trim(), deviceId)
    }

    fun unregister(token: String): Boolean = tokens.unregister(token.trim())

    /**
     * @param recipientId who would be notified
     * @param authorId    who caused it
     * @param channelId   where it happened
     */
    fun shouldNotify(recipientId: Long, authorId: Long, channelId: Long): Boolean {
        if (recipientId == authorId) return false                        // never your own
        if (authorId in social.blockedBy(recipientId)) return false       // feature 11
        if (authorId in social.mutedUsers(recipientId)) return false      // feature 11
        if (channelId in social.mutedChannels(recipientId)) return false  // feature 11

        // Feature 4. DND is only meaningfully different from Away if it actually suppresses
        // delivery — as a colour it would be decoration.
        val status = presence.presenceOf(recipientId, viewerId = recipientId).status
        if (status == app.singular.domain.PresenceStatus.DND) return false

        return true
    }

    fun deliver(message: PushMessage) {
        val devices = tokens.tokensFor(message.userId)
        if (devices.isEmpty()) return

        devices.forEach { device ->
            val transport = transports.firstOrNull { it.platform == device.platform }
                ?: transports.firstOrNull()
                ?: return@forEach

            if (!transport.send(device.token, message)) tokens.markInvalid(device.token)
        }
    }
}
