package app.singular.channel

import app.singular.domain.Channel
import app.singular.domain.ChannelType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet

@Repository
class ChannelRepository(private val jdbc: JdbcClient) {

    fun findById(id: Long): Channel? = jdbc
        .sql("$SELECT_COLS WHERE id = :id AND deleted_at IS NULL")
        .param("id", id)
        .query(::mapChannel)
        .optional()
        .orElse(null)

    fun listForUser(userId: Long): List<Channel> = jdbc
        .sql(
            """
            SELECT c.id, c.guild_id, c.type, c.name, c.icon_key, c.owner_id,
                   c.last_message_id, c.created_at
            FROM channels c
            JOIN channel_members m ON m.channel_id = c.id
            WHERE m.user_id = :userId AND c.deleted_at IS NULL
            ORDER BY c.last_message_id DESC NULLS LAST, c.id DESC
            """
        )
        .param("userId", userId)
        .query(::mapChannel)
        .list()

    fun isMember(channelId: Long, userId: Long): Boolean = jdbc
        .sql("SELECT 1 FROM channel_members WHERE channel_id = :c AND user_id = :u")
        .param("c", channelId)
        .param("u", userId)
        .query(Int::class.java)
        .optional()
        .isPresent

    fun memberIds(channelId: Long): List<Long> = jdbc
        .sql("SELECT user_id FROM channel_members WHERE channel_id = :c ORDER BY joined_at")
        .param("c", channelId)
        .query(Long::class.java)
        .list()

    /** Batch variant for the members DataLoader. */
    fun memberIdsFor(channelIds: Collection<Long>): Map<Long, List<Long>> {
        if (channelIds.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                SELECT channel_id, user_id FROM channel_members
                WHERE channel_id = ANY(:ids) ORDER BY channel_id, joined_at
                """
            )
            .param("ids", channelIds.toLongArray())
            .query { rs, _ -> rs.getLong("channel_id") to rs.getLong("user_id") }
            .list()
            .groupBy({ it.first }, { it.second })
    }

    fun findDmChannelId(userA: Long, userB: Long): Long? {
        val (low, high) = order(userA, userB)
        return jdbc
            .sql("SELECT channel_id FROM dm_pairs WHERE low_user_id = :lo AND high_user_id = :hi")
            .param("lo", low)
            .param("hi", high)
            .query(Long::class.java)
            .optional()
            .orElse(null)
    }

    fun insertChannel(id: Long, type: ChannelType, name: String?, ownerId: Long?) {
        jdbc.sql(
            """
            INSERT INTO channels (id, type, name, owner_id)
            VALUES (:id, :type, :name, :owner)
            """
        )
            .param("id", id)
            .param("type", type.code.toInt())
            .param("name", name)
            .param("owner", ownerId)
            .update()
    }

    /**
     * A channel that belongs to a server.
     *
     * Guild channels have no rows in `channel_members` — visibility comes from guild
     * membership plus the permission engine, not from an explicit member list. Adding people
     * here would create a second source of truth that immediately drifts from the first.
     */
    fun insertGuildChannel(
        id: Long,
        guildId: Long,
        type: ChannelType,
        name: String,
        parentId: Long?,
        position: Int,
        topic: String? = null,
    ) {
        jdbc.sql(
            """
            INSERT INTO channels (id, guild_id, type, name, parent_id, position, topic)
            VALUES (:id, :g, :type, :name, :parent, :pos, :topic)
            """
        )
            .param("id", id).param("g", guildId).param("type", type.code.toInt())
            .param("name", name).param("parent", parentId).param("pos", position)
            .param("topic", topic)
            .update()
    }

    fun channelsInGuild(guildId: Long): List<Channel> = jdbc
        .sql(
            """
            SELECT id, guild_id, type, name, icon_key, owner_id, last_message_id, created_at
            FROM channels
            WHERE guild_id = :g AND deleted_at IS NULL
            ORDER BY position, id
            """
        )
        .param("g", guildId)
        .query(::mapChannel)
        .list()

    fun addMembers(channelId: Long, userIds: Collection<Long>) {
        userIds.forEach { userId ->
            jdbc.sql(
                """
                INSERT INTO channel_members (channel_id, user_id)
                VALUES (:c, :u) ON CONFLICT DO NOTHING
                """
            )
                .param("c", channelId)
                .param("u", userId)
                .update()
        }
    }

    /**
     * Claims the DM pair.
     *
     * `ON CONFLICT DO NOTHING` plus a re-read is what makes openDirectMessage idempotent under
     * concurrency: if two clients open the same DM simultaneously, exactly one insert wins and
     * both end up with the winner's channel.
     */
    fun tryClaimDmPair(userA: Long, userB: Long, channelId: Long): Boolean {
        val (low, high) = order(userA, userB)
        return jdbc.sql(
            """
            INSERT INTO dm_pairs (low_user_id, high_user_id, channel_id)
            VALUES (:lo, :hi, :c) ON CONFLICT DO NOTHING
            """
        )
            .param("lo", low)
            .param("hi", high)
            .param("c", channelId)
            .update() == 1
    }

    fun updateLastMessageId(channelId: Long, messageId: Long) {
        jdbc.sql(
            """
            UPDATE channels SET last_message_id = :m
            WHERE id = :c AND (last_message_id IS NULL OR last_message_id < :m)
            """
        )
            .param("m", messageId)
            .param("c", channelId)
            .update()
    }

    fun markRead(channelId: Long, userId: Long, messageId: Long) {
        jdbc.sql(
            """
            UPDATE channel_members SET last_read_message_id = :m
            WHERE channel_id = :c AND user_id = :u
              AND (last_read_message_id IS NULL OR last_read_message_id < :m)
            """
        )
            .param("m", messageId)
            .param("c", channelId)
            .param("u", userId)
            .update()
    }

    private fun order(a: Long, b: Long) = if (a < b) a to b else b to a

    private companion object {
        const val SELECT_COLS = """
            SELECT id, guild_id, type, name, icon_key, owner_id, last_message_id, created_at
            FROM channels
        """

        fun mapChannel(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = Channel(
            id = rs.getLong("id"),
            guildId = rs.getObject("guild_id") as Long?,
            type = ChannelType.of(rs.getShort("type")),
            name = rs.getString("name"),
            iconKey = rs.getString("icon_key"),
            ownerId = rs.getObject("owner_id") as Long?,
            lastMessageId = rs.getObject("last_message_id") as Long?,
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
}
