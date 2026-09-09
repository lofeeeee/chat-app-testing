package app.singular.message

import app.singular.core.Snowflake
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

    /**
     * Several messages by id, for the channel-list previews.
     *
     * Takes the same two-sided `created_at` window as [page] rather than one predicate per id.
     * `messages` is partitioned by month, and without a bounded window the planner has to
     * touch every partition; with it, a set of ids minted within days of each other prunes to
     * one or two. The window is derived from the ids themselves — a snowflake carries its own
     * timestamp — so it costs no extra round trip.
     *
     * Deleted messages are simply absent from the result: a channel whose last message was
     * deleted shows no preview rather than a ghost.
     */
    fun findAllById(ids: Collection<Long>): Map<Long, Message> {
        if (ids.isEmpty()) return emptyMap()
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
            .param("ids", ids.toList())
            .param("from", Timestamp.from(stamps.min().minusSeconds(1)))
            .param("to", Timestamp.from(stamps.max().plusSeconds(1)))
            .query(::mapMessage)
            .list()
            .associateBy { it.id }
    }

    /**
     * Rewrite a message's body. The WHERE guards both authorisation outcomes the service has
     * already checked (existence, not deleted) so a race with a concurrent delete loses
     * cleanly here — zero rows — rather than resurrecting a tombstone.
     */
    fun edit(id: Long, createdAt: Instant, content: String, editedAt: Instant): Boolean = jdbc
        .sql(
            """
            UPDATE messages SET content = :content, edited_at = :editedAt
            WHERE id = :id AND created_at = :createdAt AND deleted_at IS NULL
            """
        )
        .param("content", content)
        .param("editedAt", Timestamp.from(editedAt))
        .param("id", id)
        .param("createdAt", Timestamp.from(createdAt))
        .update() == 1

    /**
     * Soft delete. The row stays (mention rows and pins reference it by id; hard-deleting
     * would need cascade logic across a partitioned table for no benefit), but every read
     * path filters `deleted_at IS NULL`, so it is gone as far as anyone can see.
     */
    fun softDelete(id: Long, createdAt: Instant, deletedAt: Instant): Boolean = jdbc
        .sql(
            """
            UPDATE messages SET deleted_at = :deletedAt, content = NULL
            WHERE id = :id AND created_at = :createdAt AND deleted_at IS NULL
            """
        )
        .param("deletedAt", Timestamp.from(deletedAt))
        .param("id", id)
        .param("createdAt", Timestamp.from(createdAt))
        .update() == 1

    /**
     * Unread count for one (channel, viewer): messages between the viewer's cursor and now.
     *
     * An index range scan on ix_messages_channel read backwards — the btree is ordered by
     * (channel_id, id DESC), so `id > :cursor` is a contiguous slice of it. The two-sided
     * created_at window is here for the same reason as everywhere else: partition pruning.
     */
    fun countSince(channelId: Long, afterId: Long, floor: Instant, ceiling: Instant): Int = jdbc
        .sql(
            """
            SELECT count(*) FROM messages
            WHERE channel_id = :channel
              AND created_at >= :floor
              AND created_at <= :ceiling
              AND id > :afterId
              AND deleted_at IS NULL
            """
        )
        .param("channel", channelId)
        .param("afterId", afterId)
        .param("floor", Timestamp.from(floor))
        .param("ceiling", Timestamp.from(ceiling))
        .query(Int::class.java)
        .single()

    /**
     * Batch unread counts for the sidebar: every DM/group channel the viewer is in, with how
     * many live messages sit past their cursor. One query for the whole list rather than one
     * per row — the same shape as the other sidebar batch fetches.
     *
     * Guild channels are deliberately absent here: they have no `channel_members` row, so
     * there is no cursor to count from. Guild unread state is client-side only for now.
     */
    fun unreadCountsFor(userId: Long, floor: Instant, ceiling: Instant): Map<Long, Int> = jdbc
        .sql(
            """
            SELECT m.channel_id,
                   count(*) FILTER (WHERE msg.id > m.last_read_message_id AND msg.deleted_at IS NULL) AS unread
            FROM channel_members m
            JOIN channels c ON c.id = m.channel_id AND c.deleted_at IS NULL AND c.guild_id IS NULL
            JOIN LATERAL (
                SELECT id FROM messages
                WHERE channel_id = m.channel_id
                  AND created_at >= :floor
                  AND created_at <= :ceiling
                  AND author_id <> :userId
                  AND deleted_at IS NULL
            ) msg ON true
            GROUP BY m.channel_id
            """
        )
        .param("userId", userId)
        .param("floor", Timestamp.from(floor))
        .param("ceiling", Timestamp.from(ceiling))
        .query { rs, _ -> rs.getLong("channel_id") to rs.getInt("unread") }
        .list()
        .toMap()

    /**
     * Full-text search within one channel, newest first.
     *
     * `plainto_tsquery` rather than `to_tsquery` because it takes the raw user string and
     * ANDs the terms — users type "meeting notes", not "meeting & notes", and the failure
     * mode of the strict form on free text is a syntax error returned as a 500.
     */
    fun search(
        channelId: Long,
        query: String,
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
              AND deleted_at IS NULL
              AND content_tsv @@ plainto_tsquery('simple', :query)
            ORDER BY id DESC
            LIMIT :limit
            """
        )
        .param("channel", channelId)
        .param("query", query)
        .param("limit", limit)
        .param("floor", Timestamp.from(floor))
        .param("ceiling", Timestamp.from(ceiling))
        .query(::mapMessage)
        .list()

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
