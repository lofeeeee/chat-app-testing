package app.singular.push

import app.singular.channel.ChannelRepository
import app.singular.channel.ChannelService
import app.singular.core.Snowflake
import app.singular.domain.Channel
import app.singular.domain.ChannelType
import app.singular.domain.Message
import app.singular.domain.User
import app.singular.message.Mention
import app.singular.message.MentionRepository
import app.singular.message.MentionType
import app.singular.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Turns "a message was sent" into outbox rows.
 *
 * The missing half of feature 7: `PushService.shouldNotify` and the transports existed, but
 * nothing called them — a message arriving never produced a notification, because no code
 * bridged the send path to the delivery path.
 *
 * ## Who gets a push
 *
 *  * **DMs and group DMs**: every member except the author, filtered by
 *    [PushService.shouldNotify] (blocks, mutes, DND).
 *  * **Guild channels**: *mentioned users only* — the blueprint's call, and Discord's model.
 *    A busy server channel notifying every member per message is a fanout storm and the
 *    fastest way to make everyone switch notifications off entirely.
 *
 *  Mention targets are resolved here: a user mention is that user, a role mention is everyone
 *  holding the role, and `@everyone`/`@here` is everyone in the channel. The same resolution
 *  the mentions inbox performs — one definition of "who was pinged", not two.
 *
 * ## Timing
 *
 * Rows are enqueued **after commit**. Inside the transaction they'd ride the rollback of a
 * failed send; after it, a notification for a message that never existed can't happen.
 * The enqueue work itself is off-thread — the provider is never on the send path.
 */
@Component
class PushFanout(
    private val push: PushService,
    private val outbox: PushOutboxRepository,
    private val tokens: PushTokenRepository,
    private val channels: ChannelRepository,
    private val channelService: ChannelService,
    private val mentions: MentionRepository,
    private val users: UserRepository,
    private val snowflake: Snowflake,
    private val jdbc: JdbcClient,
) {

    /**
     * A small fixed pool, not virtual threads: this work is IO-bound on Postgres, and a burst
     * of sends fanning out to thousands of recipients should queue on the pool rather than
     * opening thousands of connections. The pool is a natural backpressure point.
     */
    private val executor: Executor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "push-fanout").apply { isDaemon = true }
    }

    /** Called from the send path, inside the message transaction. */
    fun onMessageSent(message: Message, parsedMentions: List<Mention>) {
        afterCommit {
            executor.execute {
                runCatching { fanout(message, parsedMentions) }
                    .onFailure { LOG.warn("Push fanout failed for message {}", message.id, it) }
            }
        }
    }

    private fun fanout(message: Message, parsedMentions: List<Mention>) {
        val channel = channels.findById(message.channelId) ?: return

        val recipients: Set<Long> = when (channel.type) {
            ChannelType.DM, ChannelType.GROUP_DM ->
                channelService.membersOf(message.channelId).toSet()

            else -> resolveMentionedUsers(message, channel, parsedMentions)
        }

        if (recipients.isEmpty()) return

        val authorName = users.findById(message.authorId)?.label() ?: "Someone"
        val channelLabel =
            if (channel.guildId == null) ""
            else "${channel.name ?: "channel"}: "
        val body = channelLabel + bodyPreview(message)

        recipients
            .filter { it != message.authorId }
            .filter { push.shouldNotify(it, message.authorId, message.channelId) }
            .forEach { recipientId ->
                tokens.tokensFor(recipientId).forEach { device ->
                    outbox.enqueue(
                        PushOutboxRow(
                            id = snowflake.next(),
                            userId = recipientId,
                            token = device.token,
                            platform = device.platform,
                            title = authorName,
                            body = body,
                            channelId = message.channelId,
                            messageId = message.id,
                        ),
                    )
                }
            }
    }

    /**
     * Mention targets → user ids. Direct user mentions pass through; role mentions expand to
     * the role's holders; broadcasts expand to channel-visible members of the guild.
     */
    private fun resolveMentionedUsers(
        message: Message,
        channel: Channel,
        parsed: List<Mention>,
    ): Set<Long> {
        val result = mutableSetOf<Long>()

        parsed.forEach { mention ->
            when (mention.type) {
                MentionType.USER -> result += mention.targetId

                MentionType.ROLE ->
                    result += roleHolders(mention.targetId)

                MentionType.EVERYONE, MentionType.HERE -> {
                    // Broadcast mentions: everyone who can see the channel. The permission
                    // engine's full resolution is expensive per member; for a push decision,
                    // guild membership is the honest ceiling — someone who can't see the
                    // channel gets suppressed at delivery time by visibility anyway, and a
                    // false-positive here costs one suppressed notification, not a leak.
                    result += guildMemberIds(channel.guildId)
                }
            }
        }

        return result
    }

    private fun roleHolders(roleId: Long): List<Long> = jdbc
        .sql("SELECT user_id FROM member_roles WHERE role_id = :r")
        .param("r", roleId)
        .query(Long::class.java)
        .list()

    private fun guildMemberIds(guildId: Long?): List<Long> {
        if (guildId == null) return emptyList()
        return jdbc
            .sql("SELECT user_id FROM guild_members WHERE guild_id = :g")
            .param("g", guildId)
            .query(Long::class.java)
            .list()
    }

    private fun bodyPreview(message: Message): String {
        if (message.content.isNullOrBlank()) {
            return if (message.locationLat != null) "Shared a location" else "Sent an attachment"
        }
        return if (message.content.length <= PREVIEW_MAX) message.content
        else message.content.take(PREVIEW_MAX) + "…"
    }

    private fun afterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = action()
                }
            )
        } else {
            action()
        }
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(PushFanout::class.java)!!

        /** Long enough to be informative, short enough that the drawer doesn't truncate mid-word. */
        const val PREVIEW_MAX = 120

        /** Same rule the client renders with — see UserDto.label. */
        fun User.label(): String = displayName?.takeIf { it.isNotBlank() } ?: username
    }
}
