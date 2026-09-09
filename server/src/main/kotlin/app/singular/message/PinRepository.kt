package app.singular.message

import app.singular.core.Snowflake
import app.singular.domain.Message
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.sql.ResultSet
import java.time.Instant

/**
 * Pinned messages, per channel.
 *
 * A pin is a row in `message_pins`, not a flag on the message: pinning is per-channel state
 * about a message, and the message itself already has enough columns. The row records who
 * pinned for attribution; unpinning removes the row entirely, so there is no "was pinned"
 * residue to keep consistent with anything.
 *
 * Read paths join back to `messages` by id and drop the pin silently if the message is gone —
 * a deleted message cannot stay pinned, and a ghost pin in the drawer is worse than no pin.
 */
@Repository
class PinRepository(private val jdbc: JdbcClient) {

    fun pin(channelId: Long, messageId: Long, pinnedBy: Long) {
        jdbc.sql(
            """
            INSERT INTO message_pins (channel_id, message_id, pinned_by)
            VALUES (:c, :m, :u) ON CONFLICT DO NOTHING
            """
        )
            .param("c", channelId)
            .param("m", messageId)
            .param("u", pinnedBy)
            .update()
    }

    fun unpin(channelId: Long, messageId: Long): Boolean = jdbc
        .sql("DELETE FROM message_pins WHERE channel_id = :c AND message_id = :m")
        .param("c", channelId)
        .param("m", messageId)
        .update() == 1

    /** Pins for one channel, newest pin first. Deleted messages drop out here, not at render. */
    fun pinnedIn(channelId: Long): Map<Long, Message> {
        val ids = jdbc
            .sql(
                """
                SELECT message_id FROM message_pins
                WHERE channel_id = :c ORDER BY pinned_at DESC
                """
            )
            .param("c", channelId)
            .query(Long::class.java)
            .list()

        // MessageRepository.findAllById already filters deleted rows and derives the
        // partition window from the ids themselves; reusing it keeps the join logic in
        // one place. Its map preserves no order, so order is restored by the caller below.
        val found = if (ids.isEmpty()) emptyMap() else findAllById(ids)
        return ids.mapNotNull { found[it] }.associateBy { it.id }
    }

    fun isPinned(channelId: Long, messageId: Long): Boolean = jdbc
        .sql("SELECT 1 FROM message_pins WHERE channel_id = :c AND message_id = :m")
        .param("c", channelId)
        .param("m", messageId)
        .query(Int::class.java)
        .optional()
        .isPresent

    private fun findAllById(ids: List<Long>): Map<Long, Message> {
        val stamps = ids.map(Snowflake::timestampOf)
        return jdbc
            .sql(
                """
                SELECT id, channel_id, author_id, content, reply_to_id, created_at, edited_at,
                       location_lat, location_lon, location_label, location_expires_at
                FROM messages
                WHERE id IN (:ids)
                  AND created_at BETWEEN :from AND :to
                  AND deleted_at IS NULL
                """
            )
            .param("ids", ids)
            .param("from", Timestamp.from(stamps.min().minusSeconds(1)))
            .param("to", Timestamp.from(stamps.max().plusSeconds(1)))
            .query(::map)
            .list()
            .associateBy { it.id }
    }

    private companion object {
        fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = Message(
            id = rs.getLong("id"),
            channelId = rs.getLong("channel_id"),
            authorId = rs.getLong("author_id"),
            content = rs.getString("content"),
            replyToId = rs.getObject("reply_to_id") as Long?,
            createdAt = rs.getTimestamp("created_at").toInstant(),
            editedAt = rs.getTimestamp("edited_at")?.toInstant(),
            locationLat = rs.getObject("location_lat") as Double?,
            locationLon = rs.getObject("location_lon") as Double?,
            locationLabel = rs.getString("location_label"),
            locationExpiresAt = rs.getTimestamp("location_expires_at")?.toInstant(),
        )
    }
}
