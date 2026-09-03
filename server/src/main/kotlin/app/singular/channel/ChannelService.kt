package app.singular.channel

import app.singular.audit.AuditLog
import app.singular.core.Forbidden
import app.singular.core.InvalidInput
import app.singular.core.NotFound
import app.singular.core.Snowflake
import app.singular.domain.AuditAction
import app.singular.domain.Channel
import app.singular.domain.ChannelType
import app.singular.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChannelService(
    private val channels: ChannelRepository,
    private val users: UserRepository,
    private val guildService: app.singular.guild.GuildService,
    private val snowflake: Snowflake,
    private val audit: AuditLog,
) {

    fun listForUser(userId: Long): List<Channel> = channels.listForUser(userId)

    /**
     * The single visibility gate, for both kinds of channel.
     *
     * DMs and group DMs authorise on an explicit member list. **Guild channels deliberately
     * have no rows in `channel_members`** — being in the server plus holding VIEW_CHANNEL is
     * what grants access, and duplicating that into a member list would create a second source
     * of truth that drifts the moment a role changes.
     */
    fun requireVisible(channelId: Long, userId: Long): Channel {
        val channel = channels.findById(channelId) ?: throw NotFound("Channel")

        val guildId = channel.guildId
        if (guildId != null) {
            guildService.requireInChannel(
                channelId, guildId, userId, app.singular.guild.Permission.VIEW_CHANNEL,
            )
        } else if (!channels.isMember(channelId, userId)) {
            throw Forbidden("that conversation")
        }
        return channel
    }

    fun membersOf(channelId: Long) = channels.memberIds(channelId)

    /**
     * Get-or-create a 1:1 DM.
     *
     * Idempotent by construction: the `dm_pairs` primary key on the sorted user pair means two
     * clients opening the same DM at the same moment converge on one channel — the loser of the
     * insert re-reads the winner's id rather than creating a duplicate.
     */
    @Transactional
    fun openDirectMessage(selfId: Long, otherId: Long): Channel {
        if (selfId == otherId) throw InvalidInput("You can't open a DM with yourself.")
        users.findById(otherId) ?: throw NotFound("User")

        channels.findDmChannelId(selfId, otherId)?.let { existing ->
            return channels.findById(existing) ?: throw NotFound("Channel")
        }

        val channelId = snowflake.next()
        channels.insertChannel(channelId, ChannelType.DM, name = null, ownerId = null)

        if (!channels.tryClaimDmPair(selfId, otherId, channelId)) {
            // Someone else created it between our read and our insert. Their channel wins; the
            // orphaned row we just made has no members and is collected by the sweeper.
            val winner = channels.findDmChannelId(selfId, otherId)
                ?: error("DM pair claim failed but no winning channel exists")
            return channels.findById(winner) ?: throw NotFound("Channel")
        }

        channels.addMembers(channelId, listOf(selfId, otherId))
        audit.record(selfId, AuditAction.CHANNEL_CREATE, targetId = channelId)

        return channels.findById(channelId) ?: error("Channel $channelId vanished after insert")
    }

    @Transactional
    fun markRead(channelId: Long, userId: Long, messageId: Long): Boolean {
        requireVisible(channelId, userId)
        channels.markRead(channelId, userId, messageId)
        return true
    }
}
