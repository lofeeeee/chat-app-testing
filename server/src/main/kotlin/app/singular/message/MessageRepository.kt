package app.singular.message

import app.singular.domain.Message
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class MessageRepository(private val jdbc: JdbcClient) {

    fun insert(
        id: Long,
        channelId: Long,
        authorId: Long,
        content: String,
        replyToId: Long?,
        sessionId: Long?,
        createdAt: Instant,
    ) {
        jdbc.sql(
            """
            INSERT INTO messages (id, channel_id, author_id, session_id, content, reply_to_id, created_at)
            VALUES (:id, :channel, :author, :session, :content, :replyTo, :createdAt)
            """
        )
            .param("id", id)
            .param("channel", channelId)
            .param("author", authorId)
            .param("session", sessionId)
            .param("content", content)
            .param("replyTo", replyToId)
            .param("createdAt", Timestamp.from(createdAt))
            .update()
    }

    /**
     * Newest-first page, walking backwards from [before].
     *
     * The `created_at BETWEEN` clause is not redundant with the id cursor — it is the only
     * thing the planner can use to prune partitions, because it cannot know that snowflake ids
     * encode time.
     *
     * The casts around `:before` are load-bearing, not noise. A bare `? IS NULL` gives Postgres
     * no type context for the placeholder and it fails the whole statement with
     * "could not determine data type of parameter" — at runtime, on the first page load.
     *
     * **Both bounds are required.** A floor alone prunes older partitions but still scans every
     * future one: verified against Postgres 17, a one-sided predicate over three years of
     * monthly partitions planned 17 index scans where a two-sided one planned 4. Paging
     * backwards means every result is older than the cursor, so the ceiling is free.
     */
    fun page(
        channelId: Long,
        before: Long?,
        limit: Int,
        floor: Instant,
        ceiling: Instant,
    ): List<Message> = jdbc
        .sql(
            """
            SELECT id, channel_id, author_id, content, reply_to_id, created_at, edited_at,
                   location_lat, location_lon, location_label, location_expires_at
            FROM messages
            WHERE channel_id = :channel
              AND created_at >= :floor
              AND created_at <= :ceiling
              AND (CAST(:before AS bigint) IS NULL OR id < CAST(:before AS bigint))
              AND deleted_at IS NULL
            ORDER BY id DESC
            LIMIT :limit
            """
        )
        .param("channel", channelId)
        .param("before", before)
        .param("floor", Timestamp.from(floor))
        .param("ceiling", Timestamp.from(ceiling))
        .param("limit", limit)
        .query(::mapMessage)
        .list()

    fun insertLocation(
        id: Long,
        channelId: Long,
        authorId: Long,
        lat: Double,
        lon: Double,
        label: String?,
        expiresAt: Instant?,
        sessionId: Long?,
        createdAt: Instant,
    ) {
        jdbc.sql(
            """
            INSERT INTO messages (id, channel_id, author_id, session_id, content, created_at,
                                  location_lat, location_lon, location_label, location_expires_at)
            VALUES (:id, :c, :a, :s, :label, :at, :lat, :lon, :label, :exp)
            """
        )
            .param("id", id).param("c", channelId).param("a", authorId).param("s", sessionId)
            .param("label", label ?: "Shared a location")
            .param("at", Timestamp.from(createdAt))
            .param("lat", lat).param("lon", lon)
            .param("exp", expiresAt?.let(Timestamp::from))
            .update()
    }

    fun findById(id: Long, createdAt: Instant): Message? = jdbc
        .sql(
            """
            SELECT id, channel_id, author_id, content, reply_to_id, created_at, edited_at,
                   location_lat, location_lon, location_label, location_expires_at
            FROM messages
            WHERE id = :id AND created_at = :createdAt AND deleted_at IS NULL
            """
        )
        .param("id", id)
        .param("createdAt", Timestamp.from(createdAt))
        .query(::mapMessage)
        .optional()
        .orElse(null)

    // -- Idempotency ---------------------------------------------------------
    //
    // Postgres requires a unique index on a partitioned table to include every partition key
    // column. A unique index on (channel, author, nonce, created_at) would be useless for
    // dedup, since a retry carries a different timestamp. So nonces live in their own
    // unpartitioned table, reaped after 24h by MessageReaper.

    fun claimNonce(channelId: Long, authorId: Long, nonce: String, messageId: Long): Boolean =
        jdbc.sql(
            """
            INSERT INTO message_nonces (channel_id, author_id, nonce, message_id)
            VALUES (:c, :a, :n, :m) ON CONFLICT DO NOTHING
            """
        )
            .param("c", channelId)
            .param("a", authorId)
            .param("n", nonce)
            .param("m", messageId)
            .update() == 1

    fun findByNonce(channelId: Long, authorId: Long, nonce: String): Long? = jdbc
        .sql(
            """
            SELECT message_id FROM message_nonces
            WHERE channel_id = :c AND author_id = :a AND nonce = :n
            """
        )
        .param("c", channelId)
        .param("a", authorId)
        .param("n", nonce)
        .query(Long::class.java)
        .optional()
        .orElse(null)

    fun reapNonces(olderThan: Instant): Int = jdbc
        .sql("DELETE FROM message_nonces WHERE created_at < :cutoff")
        .param("cutoff", Timestamp.from(olderThan))
        .update()

    private companion object {
        fun mapMessage(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = Message(
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
