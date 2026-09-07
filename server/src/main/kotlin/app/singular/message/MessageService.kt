package app.singular.message

import app.singular.channel.ChannelRepository
import app.singular.channel.ChannelService
import app.singular.config.SingularProperties
import app.singular.core.Forbidden
import app.singular.core.InvalidInput
import app.singular.core.NotFound
import app.singular.core.Snowflake
import app.singular.domain.ChannelType
import app.singular.social.SocialRepository
import app.singular.domain.Message
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import reactor.core.publisher.Flux
import java.time.Instant

data class MessagePage(
    val nodes: List<Message>,
    val nextCursor: Long?,
    val hasMore: Boolean,
)

@Service
class MessageService(
    private val messages: MessageRepository,
    private val channels: ChannelRepository,
    private val channelService: ChannelService,
    private val events: MessageEvents,
    private val social: SocialRepository,
    private val mentionParser: MentionParser,
    private val mentions: MentionRepository,
    private val reactions: ReactionRepository,
    private val reactionEvents: ReactionEvents,
    private val guilds: app.singular.guild.GuildRepository,
    private val guildService: app.singular.guild.GuildService,
    private val media: app.singular.media.MediaService,
    private val pushFanout: app.singular.push.PushFanout,
    private val snowflake: Snowflake,
    private val props: SingularProperties,
) {

    @Transactional
    fun send(
        channelId: Long,
        authorId: Long,
        content: String,
        replyToId: Long?,
        nonce: String?,
        sessionId: Long?,
        attachmentIds: List<Long> = emptyList(),
    ): Message {
        val channel = channelService.requireVisible(channelId, authorId)

        // In a DM a block stops delivery in both directions. Elsewhere the message still goes
        // through and is flagged for the client to collapse -- a shared server shouldn't go
        // silent for everyone just because two of its members fell out.
        if (channel.type == ChannelType.DM) {
            val other = channels.memberIds(channelId).firstOrNull { it != authorId }
            if (other != null && social.blockExistsEitherWay(authorId, other)) {
                throw Forbidden("this conversation")
            }
        }

        val body = content.trim()
        // A message carrying a file needs no words. Requiring text would make sending a photo
        // on its own impossible.
        if (body.isEmpty() && attachmentIds.isEmpty()) {
            throw InvalidInput("Message can't be empty.")
        }
        if (body.length > props.limits.messageMaxLength) {
            throw InvalidInput("Messages are limited to ${props.limits.messageMaxLength} characters.")
        }
        if (nonce != null && nonce.length > 64) {
            throw InvalidInput("Nonce too long.")
        }

        val id = snowflake.next()
        // The snowflake already encodes creation time — deriving created_at from it keeps the
        // id and the partition key perfectly consistent. Taking a second Instant.now() here
        // would let a message land in a partition its id doesn't imply, right on a month boundary.
        val createdAt = Snowflake.timestampOf(id)

        if (nonce != null && !messages.claimNonce(channelId, authorId, nonce, id)) {
            // A retry of a send that already landed. Return the original rather than a duplicate
            // — this is what makes "resend on flaky connection" safe for the client.
            val existingId = messages.findByNonce(channelId, authorId, nonce)
                ?: throw IllegalStateException("Nonce claimed but no message recorded")
            return messages.findById(existingId, Snowflake.timestampOf(existingId))
                ?: throw NotFound("Message")
        }

        messages.insert(id, channelId, authorId, body, replyToId, sessionId, createdAt)
        channels.updateLastMessageId(channelId, id)

        // Claimed after the message row exists so the foreign key is always satisfiable, and
        // inside the same transaction so a failed claim rolls the message back with it.
        if (attachmentIds.isNotEmpty()) {
            media.attachToMessage(attachmentIds, id, channelId, authorId)
        }

        // Feature 12. Parsed once here rather than re-scanned on every read: the body is the
        // source of truth for rendering, this table is the index for notifications and the
        // mentions inbox.
        val parsed = mentionParser.parse(body)
        if (parsed.isNotEmpty()) {
            mentions.record(id, channelId, channel.guildId, createdAt, parsed)
        }

        val message = Message(
            id = id,
            channelId = channelId,
            authorId = authorId,
            content = body,
            replyToId = replyToId,
            createdAt = createdAt,
            editedAt = null,
        )

        // Publish only after the transaction commits. Emitting inline would let a subscriber
        // receive a message that a subsequent rollback erases — a ghost in the client's
        // timeline that only a refresh clears. Push fanout rides the same rule, and the
        // parsed mentions travel with it so the fanout never re-parses the body.
        afterCommit { events.publish(message) }
        pushFanout.onMessageSent(message, parsed)

        return message
    }

    /**
     * Shares a location (part of feature 6).
     *
     * A separate path from [send] because a location has no body of its own and no attachment
     * — squeezing it through the text path would mean inventing a content string and then
     * teaching every reader to recognise it.
     */
    @Transactional
    fun sendLocation(
        channelId: Long,
        authorId: Long,
        latitude: Double,
        longitude: Double,
        label: String?,
        expiresAt: Instant?,
        sessionId: Long?,
    ): Message {
        val channel = channelService.requireVisible(channelId, authorId)

        if (channel.type == ChannelType.DM) {
            val other = channels.memberIds(channelId).firstOrNull { it != authorId }
            if (other != null && social.blockExistsEitherWay(authorId, other)) {
                throw Forbidden("this conversation")
            }
        }

        val id = snowflake.next()
        val createdAt = Snowflake.timestampOf(id)
        messages.insertLocation(
            id, channelId, authorId, latitude, longitude,
            label?.take(120), expiresAt, sessionId, createdAt,
        )
        channels.updateLastMessageId(channelId, id)

        val message = Message(
            id = id,
            channelId = channelId,
            authorId = authorId,
            content = label ?: "Shared a location",
            replyToId = null,
            createdAt = createdAt,
            editedAt = null,
            locationLat = latitude,
            locationLon = longitude,
            locationLabel = label,
            locationExpiresAt = expiresAt,
        )
        afterCommit { events.publish(message) }
        // A shared location notifies like a message — the preview says "Shared a location"
        // rather than quoting two coordinates.
        pushFanout.onMessageSent(message, emptyList())
        return message
    }

    fun page(channelId: Long, userId: Long, before: Long?, limit: Int?): MessagePage {
        channelService.requireVisible(channelId, userId)

        val size = (limit ?: 50).coerceIn(1, props.limits.messagesPageSizeMax)

        val window = partitionWindow(before)

        // Fetch one extra to detect a further page without a second COUNT query.
        val rows = messages.page(channelId, before, size + 1, window.start, window.endInclusive)
        val hasMore = rows.size > size
        val page = if (hasMore) rows.take(size) else rows

        return MessagePage(
            nodes = page,
            nextCursor = page.lastOrNull()?.id,
            hasMore = hasMore,
        )
    }

    /**
     * One message, but only if the viewer is allowed to see its channel.
     *
     * Backs the mentions inbox: being mentioned somewhere does not by itself grant the right
     * to read it, and a role you have since lost must not leave old mentions readable.
     */
    fun findVisible(messageId: Long, userId: Long): Message? {
        val message = messages.findById(messageId, Snowflake.timestampOf(messageId)) ?: return null
        return runCatching { channelService.requireVisible(message.channelId, userId) }
            .map { message }
            .getOrNull()
    }

    fun subscribe(channelId: Long, userId: Long): Flux<Message> {
        // Authorise once, at subscribe time. A membership revoked mid-stream is handled in
        // phase 2 by closing the socket on the leave event, not by re-checking per message.
        channelService.requireVisible(channelId, userId)
        return events.subscribe(channelId)
    }

    /**
     * Adds a reaction. Permission-gated in guild channels through the same channel-scoped
     * engine as posting — `ADD_REACTIONS` is a real flag in the bitfield, default-granted to
     * @everyone, and a channel overwrite denying it must hold here too. DMs have no permission
     * model; visibility (both parties being in the channel) is the whole check.
     *
     * The event carries the full post-change summary so slow clients re-render from a snapshot
     * rather than applying a delta to a count they may have lost.
     */
    @Transactional
    fun addReaction(messageId: Long, userId: Long, emoji: String): Message {
        val clean = emoji.trim()
        if (clean.isEmpty() || clean.length > REACTION_EMOJI_MAX) {
            throw InvalidInput("Reaction must be a single emoji.")
        }
        val message = messages.findById(messageId, Snowflake.timestampOf(messageId))
            ?: throw NotFound("Message")
        val channel = channelService.requireVisible(message.channelId, userId)
        if (channel.guildId != null) {
            guildService.requireInChannel(
                message.channelId, channel.guildId, userId, app.singular.guild.Permission.ADD_REACTIONS,
            )
        }
        reactions.add(messageId, message.channelId, userId, clean)
        afterCommit { reactionEvents.publish(buildUpdate(messageId, message.channelId, userId)) }
        return message
    }

    @Transactional
    fun removeReaction(messageId: Long, userId: Long, emoji: String): Message {
        val message = messages.findById(messageId, Snowflake.timestampOf(messageId))
            ?: throw NotFound("Message")
        channelService.requireVisible(message.channelId, userId)
        // Removing your own reaction needs no permission — a deny on ADD_REACTIONS stops you
        // adding new ones, not withdrawing one you already left. Compare Discord.
        reactions.remove(messageId, userId, emoji.trim())
        afterCommit { reactionEvents.publish(buildUpdate(messageId, message.channelId, userId)) }
        return message
    }

    fun subscribeReactions(channelId: Long, userId: Long): Flux<ReactionUpdate> {
        channelService.requireVisible(channelId, userId)
        return reactionEvents.subscribe(channelId)
    }

    /**
     * The post-change snapshot for a reaction event. `me` is resolved against the actor, which
     * is wrong for every other viewer — so subscribers ignore `me` on the wire and recompute it
     * locally; the field is only authoritative in the mutation's own return value.
     */
    private fun buildUpdate(messageId: Long, channelId: Long, actorId: Long) = ReactionUpdate(
        messageId = messageId,
        channelId = channelId,
        reactions = reactions.summariesFor(listOf(messageId), actorId)[messageId].orEmpty(),
    )


    /**
     * Every channel this user can read, merged into one stream. Backs notifications.
     *
     * Guild channels are filtered through [ChannelService.requireVisible] rather than assumed
     * readable from membership alone: a server you are in can perfectly well contain channels
     * a role overwrite hides from you, and those must not leak through a notification either.
     *
     * Skips your own messages — see the note on the resolver.
     */
    fun subscribeAll(userId: Long): Flux<Message> {
        val direct = channels.listForUser(userId).map { it.id }
        val guild = guilds.guildsForUser(userId)
            .flatMap { channels.channelsInGuild(it.id) }
            .filter { runCatching { channelService.requireVisible(it.id, userId) }.isSuccess }
            .map { it.id }

        val ids = (direct + guild).distinct()
        if (ids.isEmpty()) return Flux.empty()

        // Concurrency is passed explicitly. `Flux.merge`'s default caps at 32 inner sources
        // and subscribes to the rest only as earlier ones complete — but these never
        // complete, so anyone in more than 32 channels would silently never be notified
        // about the 33rd onwards.
        return Flux.merge(Flux.fromIterable(ids.map(events::subscribe)), ids.size)
            .filter { it.authorId != userId }
    }

    /**
     * The `created_at` window handed to the planner so it can prune partitions.
     *
     * Both ends matter. The ceiling is the cursor's own embedded timestamp — paging backwards,
     * nothing newer can appear — and without it the planner still scans every partition after
     * the floor. Measured on Postgres 17 across 36 monthly partitions: one-sided planned 17
     * index scans, two-sided planned 4.
     */
    private fun partitionWindow(before: Long?): ClosedRange<Instant> {
        val anchor = before?.let(Snowflake::timestampOf) ?: Instant.now()
        // A second of slack on the ceiling: a message minted in the same millisecond as the
        // cursor is excluded by `id < before` anyway, and clock skew shouldn't drop it earlier.
        return anchor.minus(PARTITION_LOOKBACK)..anchor.plusSeconds(1)
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
        /**
         * How far back a single page may reach. A quiet channel whose last activity predates
         * this window returns an empty page rather than scanning years of partitions; the
         * client pages again with an older cursor. Widen it if you have channels that idle for
         * longer than a quarter and users who scroll straight through.
         */
        val PARTITION_LOOKBACK: java.time.Duration = java.time.Duration.ofDays(120)

        /**
         * Cap on a single reaction's stored length. A ZWJ sequence (family, profession) or a
         * flag runs to ~7 code points / ~28 UTF-8 bytes; this ceiling admits every real emoji
         * while rejecting a pasted paragraph masquerading as one.
         */
        const val REACTION_EMOJI_MAX = 64
    }
}
