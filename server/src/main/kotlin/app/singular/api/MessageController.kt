package app.singular.api

import app.singular.domain.Message
import app.singular.domain.User
import app.singular.message.MessagePage
import app.singular.ratelimit.RateLimiter
import app.singular.message.Mention
import app.singular.message.MentionRepository
import app.singular.message.MessageService
import app.singular.message.ReactionRepository
import app.singular.message.ReactionSummary
import app.singular.message.ReactionUpdate
import app.singular.message.TypingEvent
import app.singular.message.TypingEvents
import app.singular.security.principalOrNull
import app.singular.security.requirePrincipal
import app.singular.social.SocialRepository
import app.singular.user.UserRepository
import graphql.GraphQLContext
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.graphql.data.method.annotation.SubscriptionMapping
import org.springframework.stereotype.Controller
import reactor.core.publisher.Flux

data class SendMessageInput(
    val channelId: Long,
    val content: String,
    val replyToId: Long? = null,
    val nonce: String? = null,
    val attachmentIds: List<Long> = emptyList(),
)

@Controller
class MessageController(
    private val messageService: MessageService,
    private val typing: TypingEvents,
    private val channelService: app.singular.channel.ChannelService,
    private val social: SocialRepository,
    private val mentions: MentionRepository,
    private val reactions: ReactionRepository,
    private val users: UserRepository,
    private val rateLimiter: RateLimiter,
) {

    /** Feature 12: everything aimed at you, newest first. */
    @QueryMapping
    fun mentionInbox(@Argument limit: Int?, ctx: GraphQLContext): List<Message> {
        val me = ctx.requirePrincipal().userId
        val ids = mentions.inboxFor(me, (limit ?: 50).coerceIn(1, 200))
        return ids.mapNotNull { messageService.findVisible(it, me) }
    }

    @QueryMapping
    fun messages(
        @Argument channelId: Long,
        @Argument before: Long?,
        @Argument limit: Int?,
        ctx: GraphQLContext,
    ): MessagePage = messageService.page(channelId, ctx.requirePrincipal().userId, before, limit)

    /**
     * Flood control, keyed per user. Generous on purpose — this stops one account pasting a
     * script's output into a channel, not a fast typist; the failure mode of limiting a chatty
     * human is a support ticket, not an incident. Nonce-based idempotency (in MessageService)
     * already absorbs client retries, so legitimate retries don't consume this bucket.
     */
    @MutationMapping
    fun sendMessage(@Argument input: SendMessageInput, ctx: GraphQLContext): Message {
        val principal = ctx.requirePrincipal()
        rateLimiter.acquireOrThrow("send-message", principal.userId.toString())
        return messageService.send(
            channelId = input.channelId,
            authorId = principal.userId,
            content = input.content,
            replyToId = input.replyToId,
            nonce = input.nonce,
            sessionId = principal.sessionId,
            attachmentIds = input.attachmentIds,
        )
    }

    @SubscriptionMapping
    fun messageCreated(@Argument channelId: Long, ctx: GraphQLContext): Flux<Message> =
        messageService.subscribe(channelId, ctx.requirePrincipal().userId)

    /**
     * Everything addressed to you, across every channel — what desktop notifications listen on.
     *
     * `messageCreated` is per-channel and therefore only ever covers the conversation you have
     * open, which is precisely the one you do *not* need to be told about. This merges the
     * fanout for every channel you can currently read into one stream, so a client needs one
     * socket rather than one per conversation.
     *
     * Your own messages are filtered out here rather than in each client: a notification for
     * something you just typed is never wanted, and leaving it to clients means whichever one
     * forgets is the one that annoys you.
     *
     * Membership is resolved **once, at subscribe time**. A channel created after you
     * subscribed is not in the stream — the client reopens this subscription when its channel
     * list changes, which is the same moment it would need the new channel anyway.
     */
    @SubscriptionMapping
    fun notifications(ctx: GraphQLContext): Flux<Message> =
        messageService.subscribeAll(ctx.requirePrincipal().userId)

    @MutationMapping
    fun startTyping(@Argument channelId: Long, ctx: GraphQLContext): Boolean {
        val principal = ctx.requirePrincipal()
        // Membership check, same as sending: otherwise anyone holding a channel id could make
        // a stranger's client show them as typing.
        channelService.requireVisible(channelId, principal.userId)
        typing.publish(TypingEvent(channelId, principal.userId))
        return true
    }

    @MutationMapping
    fun addReaction(@Argument messageId: Long, @Argument emoji: String, ctx: GraphQLContext): Message =
        messageService.addReaction(messageId, ctx.requirePrincipal().userId, emoji)

    @MutationMapping
    fun removeReaction(@Argument messageId: Long, @Argument emoji: String, ctx: GraphQLContext): Message =
        messageService.removeReaction(messageId, ctx.requirePrincipal().userId, emoji)

    /**
     * Live reaction counts for a channel, so every viewer's chips update as others react.
     *
     * The `me` flag in the payload is resolved against the *actor*, not the subscriber, so it
     * is meaningless on the wire — clients recompute their own reacted-state locally from the
     * emoji set and only take the counts from the event.
     */
    @SubscriptionMapping
    fun reactionUpdated(@Argument channelId: Long, ctx: GraphQLContext): Flux<ReactionUpdate> =
        messageService.subscribeReactions(channelId, ctx.requirePrincipal().userId)

    @SubscriptionMapping
    fun typing(@Argument channelId: Long, ctx: GraphQLContext): Flux<TypingEvent> {
        val principal = ctx.requirePrincipal()
        channelService.requireVisible(channelId, principal.userId)
        // Filter your own notices out here rather than in every client. One place to get right.
        return typing.subscribe(channelId).filter { it.userId != principal.userId }
    }

    @SchemaMapping(typeName = "TypingEvent", field = "user")
    fun typingUser(event: TypingEvent): User? = users.findById(event.userId)

    @BatchMapping(typeName = "Message", field = "author")
    fun authors(messages: List<Message>): Map<Message, User> {
        val loaded = users.findAllById(messages.map { it.authorId }.toSet())
        return messages.mapNotNull { msg -> loaded[msg.authorId]?.let { msg to it } }.toMap()
    }

    /**
     * Whether the viewer has blocked each author.
     *
     * Batched: resolving this per message would re-read the block list once per row. One fetch
     * per request instead, because a person's block list is small and every row needs it.
     *
     * The message body is still delivered. The client collapses it to "Blocked message - show"
     * and can reveal it with no round trip; stripping the content server-side would force a
     * second request just to read something the viewer already asked to see. In a DM a block
     * stops delivery outright, so this is only ever true in shared servers and group DMs.
     */
    @BatchMapping(typeName = "Message", field = "mentions")
    fun messageMentions(messages: List<Message>): Map<Message, List<Mention>> {
        val loaded = mentions.mentionsOf(messages.map { it.id })
        return messages.associateWith { loaded[it.id].orEmpty() }
    }

    /**
     * The reaction chips under each message. Batched for the same reason as mentions — one
     * grouped query per page, not one per row. `me` is resolved against the requesting viewer
     * here, so on this read path it is authoritative (unlike on the subscription wire).
     */
    @BatchMapping(typeName = "Message", field = "reactions")
    fun messageReactions(messages: List<Message>, ctx: GraphQLContext): Map<Message, List<ReactionSummary>> {
        val viewer = ctx.principalOrNull()?.userId ?: 0L
        val loaded = reactions.summariesFor(messages.map { it.id }, viewer)
        return messages.associateWith { loaded[it.id].orEmpty() }
    }

    @SchemaMapping(typeName = "Mention", field = "type")
    fun mentionType(m: Mention): String = m.type.name

    /** @everyone and @here have no target, so 0 is reported as null rather than a fake id. */
    @SchemaMapping(typeName = "Mention", field = "targetId")
    fun mentionTarget(m: Mention): Long? = m.targetId.takeIf { it != 0L }

    @BatchMapping(typeName = "Message", field = "authorBlocked")
    fun authorBlocked(messages: List<Message>, ctx: GraphQLContext): Map<Message, Boolean> {
        val viewer = ctx.principalOrNull() ?: return messages.associateWith { false }
        val blocked = social.blockedBy(viewer.userId)
        return messages.associateWith { it.authorId in blocked }
    }
}
