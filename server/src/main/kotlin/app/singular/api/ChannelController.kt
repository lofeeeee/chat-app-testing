package app.singular.api

import app.singular.channel.ChannelRepository
import app.singular.channel.ChannelService
import app.singular.domain.Channel
import app.singular.domain.Message
import app.singular.domain.User
import app.singular.message.MessageRepository
import app.singular.security.requirePrincipal
import app.singular.user.UserRepository
import graphql.GraphQLContext
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.stereotype.Controller

@Controller
class ChannelController(
    private val channelService: ChannelService,
    private val channels: ChannelRepository,
    private val users: UserRepository,
    private val messages: MessageRepository,
) {

    @QueryMapping
    fun channels(ctx: GraphQLContext): List<Channel> =
        channelService.listForUser(ctx.requirePrincipal().userId)

    @QueryMapping
    fun channel(@Argument id: Long, ctx: GraphQLContext): Channel =
        channelService.requireVisible(id, ctx.requirePrincipal().userId)

    @MutationMapping
    fun openDirectMessage(@Argument userId: Long, ctx: GraphQLContext): Channel =
        channelService.openDirectMessage(ctx.requirePrincipal().userId, userId)

    @MutationMapping
    fun createGroupDm(
        @Argument userIds: List<Long>,
        @Argument name: String?,
        ctx: GraphQLContext,
    ): Channel = channelService.createGroupDm(ctx.requirePrincipal().userId, userIds, name)

    @MutationMapping
    fun markRead(
        @Argument channelId: Long,
        @Argument messageId: Long,
        ctx: GraphQLContext,
    ): Boolean = channelService.markRead(channelId, ctx.requirePrincipal().userId, messageId)

    /**
     * Batched: two queries for a list of N channels, not 2N.
     *
     * Resolving `members` per channel is the classic N+1 — invisible with ten test channels,
     * and the first thing that falls over with a real sidebar open.
     */
    @BatchMapping(typeName = "Channel", field = "members")
    fun members(channels: List<Channel>): Map<Channel, List<User>> {
        val idsByChannel = this.channels.memberIdsFor(channels.map { it.id })
        val allUsers = users.findAllById(idsByChannel.values.flatten().toSet())

        return channels.associateWith { channel ->
            idsByChannel[channel.id].orEmpty().mapNotNull { allUsers[it] }
        }
    }

    /**
     * The newest message in each channel, for the sidebar preview.
     *
     * Batched for the same reason as `members`: a sidebar of N conversations must not cost N
     * message lookups. `last_message_id` is already denormalised onto the row by the send
     * path, so this is one keyed fetch rather than N "newest row per channel" scans.
     *
     * Absent for a channel whose last message was deleted — the preview then falls back to
     * nothing rather than showing a message that isn't there any more.
     */
    @BatchMapping(typeName = "Channel", field = "lastMessage")
    fun lastMessages(channels: List<Channel>): Map<Channel, Message> {
        val loaded = messages.findAllById(channels.mapNotNull { it.lastMessageId }.toSet())
        // Channels with nothing to show are left out of the map entirely rather than mapped
        // to null: an absent key is how a batch mapping says "null", and null *values* are
        // not something every map the loader passes through will tolerate.
        return channels.mapNotNull { channel ->
            channel.lastMessageId?.let(loaded::get)?.let { channel to it }
        }.toMap()
    }
}
