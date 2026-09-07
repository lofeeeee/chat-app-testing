package app.singular.channel

import app.singular.audit.AuditLog
import app.singular.core.Forbidden
import app.singular.core.InvalidInput
import app.singular.core.NotFound
import app.singular.core.Snowflake
import app.singular.domain.AuditAction
import app.singular.domain.Channel
import app.singular.domain.ChannelType
import app.singular.social.SocialRepository
import app.singular.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ChannelService(
    private val channels: ChannelRepository,
    private val users: UserRepository,
    private val guildService: app.singular.guild.GuildService,
    private val social: SocialRepository,
    private val snowflake: Snowflake,
    private val audit: AuditLog,
) {

    private companion object {
        /**
         * Ceiling on group DM size. Discord's is nine others and ten years of usage says it's
         * the right shape: bigger than that is a server with worse tools, and the fanout and
         * the "everyone can see everyone" UI both stop being fun well before this anyway.
         */
        const val MAX_GROUP_DM_OTHERS = 9
    }

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

    /**
     * Feature 2's missing half: create a group DM.
     *
     * The schema has carried `type = GROUP_DM`, `owner_id` and the member list since the
     * baseline migration — only the path in was missing. The owner is recorded because group
     * DMs are the one kind of conversation with someone who can vouch for its shape: inviting
     * more people later is an owner-only action, which is the difference between "a group
     * chat" and "a way to put anyone in a room with anyone else".
     */
    @Transactional
    fun createGroupDm(selfId: Long, memberIds: List<Long>, name: String?): Channel {
        val others = memberIds.distinct()
        if (others.isEmpty()) throw InvalidInput("Pick at least one person.")
        if (others.size > MAX_GROUP_DM_OTHERS) {
            throw InvalidInput("Group conversations are limited to $MAX_GROUP_DM_OTHERS others.")
        }
        if (selfId in others) throw InvalidInput("You're already in the group — pick others.")

        others.forEach { id ->
            users.findById(id) ?: throw NotFound("User")
            // A block in either direction means the group would put two people in a
            // conversation one of them paid to avoid — the same rule DM sending enforces.
            if (social.blockExistsEitherWay(selfId, id)) {
                throw Forbidden("that conversation")
            }
        }

        val cleanName = name?.trim()?.takeIf { it.isNotEmpty() }
            ?: defaultGroupName(selfId, others)

        val channelId = snowflake.next()
        channels.insertChannel(channelId, ChannelType.GROUP_DM, name = cleanName, ownerId = selfId)
        channels.addMembers(channelId, others + selfId)
        audit.record(selfId, AuditAction.CHANNEL_CREATE, targetId = channelId)

        return channels.findById(channelId) ?: error("Channel $channelId vanished after insert")
    }

    /** "You, Alex and Sam" — a name the owner can rename later, not a wall of ids. */
    private fun defaultGroupName(selfId: Long, others: List<Long>): String {
        val names = others.mapNotNull { users.findById(it)?.username }
        val everyone = (listOf(users.findById(selfId)?.username ?: "You") + names)
        return when {
            everyone.size <= 3 -> everyone.joinToString(", ")
            else -> everyone.take(3).joinToString(", ") + " and ${everyone.size - 3} more"
        }.take(64)
    }

    @Transactional
    fun markRead(channelId: Long, userId: Long, messageId: Long): Boolean {
        requireVisible(channelId, userId)
        channels.markRead(channelId, userId, messageId)
        return true
    }
}
