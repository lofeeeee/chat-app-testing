package app.singular.push

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** One queued notification. */
data class PushOutboxRow(
    val id: Long,
    val userId: Long,
    val token: String,
    val platform: PushPlatform,
    val title: String,
    val body: String,
    val channelId: Long?,
    val messageId: Long?,
    val attempts: Int,
)

/**
 * The durable queue between "a message was sent" and "the provider was called".
 *
 * Enqueue happens in the message-send path (after commit); dispatch happens in the worker.
 * The split is the whole point: a provider outage or a slow APNs round trip must never sit
 * inside a GraphQL mutation, and a notification lost to a restart must not be lost at all —
 * it's still in the table, still due.
 */
@Repository
class PushOutboxRepository(private val jdbc: JdbcClient) {

    fun enqueue(row: PushOutboxRow) {
        jdbc.sql(
            """
            INSERT INTO push_outbox
                (id, user_id, token, platform, title, body, channel_id, message_id, attempts)
            VALUES (:id, :u, :t, :p, :title, :body, :c, :m, 0)
            """
        )
            .param("id", row.id)
            .param("u", row.userId)
            .param("t", row.token)
            .param("p", row.platform.code.toInt())
            .param("title", row.title)
            .param("body", row.body)
            .param("c", row.channelId)
            .param("m", row.messageId)
            .update()
    }

    /**
     * Up to [limit] undelivered rows whose backoff has elapsed, oldest attempt first.
     *
     * No row locking here: single-dispatcher behaviour comes from the worker's distributed
     * lease, and a lease that expires mid-batch costs at most a duplicate send (both FCM and
     * APNs dedupe by token+message), never a lost one — which is the right trade for a
     * notification queue.
     */
    fun dueBatch(limit: Int): List<PushOutboxRow> = jdbc
        .sql(
            """
            SELECT id, user_id, token, platform, title, body, channel_id, message_id, attempts
            FROM push_outbox
            WHERE delivered_at IS NULL AND failed_at IS NULL AND next_attempt <= now()
            ORDER BY next_attempt, id
            LIMIT :limit
            """
        )
        .param("limit", limit)
        .query { rs, _ ->
            PushOutboxRow(
                id = rs.getLong("id"),
                userId = rs.getLong("user_id"),
                token = rs.getString("token"),
                platform = PushPlatform.ofCode(rs.getShort("platform")),
                title = rs.getString("title"),
                body = rs.getString("body"),
                channelId = rs.getObject("channel_id") as Long?,
                messageId = rs.getObject("message_id") as Long?,
                attempts = rs.getInt("attempts"),
            )
        }
        .list()

    fun markDelivered(id: Long) {
        jdbc.sql("UPDATE push_outbox SET delivered_at = now() WHERE id = :id")
            .param("id", id).update()
    }

    /**
     * Schedule the next try with exponential backoff, or give up when the ceiling is reached.
     *
     * Giving up writes `failed_at` rather than deleting: "this notification never arrived" is
     * a question this table should always be able to answer.
     */
    fun markAttemptFailed(id: Long, attempts: Int, maxAttempts: Int) {
        if (attempts >= maxAttempts) {
            jdbc.sql("UPDATE push_outbox SET failed_at = now(), attempts = :a WHERE id = :id")
                .param("a", attempts).param("id", id).update()
        } else {
            // 10s, 20s, 40s … capped so an unlucky row can't end up waiting days.
            val backoffSeconds = (10L shl (attempts - 1).coerceAtMost(8)).coerceAtMost(3600L)
            jdbc.sql(
                """
                UPDATE push_outbox
                SET attempts = :a, next_attempt = now() + make_interval(secs => :s)
                WHERE id = :id
                """
            )
                .param("a", attempts).param("s", backoffSeconds).param("id", id).update()
        }
    }

    /**
     * Deletes settled rows older than the retention window.
     *
     * Delivered and dead-letter rows alike: the outbox is a queue, not an archive — the audit
     * and delivery logs of the provider are where notification history belongs.
     */
    fun reapSettled(before: Instant): Int = jdbc
        .sql("DELETE FROM push_outbox WHERE (delivered_at IS NOT NULL OR failed_at IS NOT NULL) AND created_at < :before")
        .param("before", Timestamp.from(before))
        .update()
}
