package app.singular.media

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

@Repository
class AttachmentRepository(private val jdbc: JdbcClient) {

    fun insert(
        id: Long,
        uploaderId: Long,
        objectKey: String,
        filename: String,
        contentType: String,
        sizeBytes: Long,
        kind: AttachmentKind,
    ) {
        jdbc.sql(
            """
            INSERT INTO attachments
                (id, uploader_id, object_key, filename, content_type, size_bytes, kind, status)
            VALUES (:id, :u, :key, :name, :type, :size, :kind, 0)
            """
        )
            .param("id", id).param("u", uploaderId).param("key", objectKey)
            .param("name", filename).param("type", contentType).param("size", sizeBytes)
            .param("kind", kind.code.toInt())
            .update()
    }

    fun find(id: Long): Attachment? = jdbc
        .sql("$COLS WHERE id = :id")
        .param("id", id)
        .query(::map)
        .optional()
        .orElse(null)

    fun forMessages(messageIds: Collection<Long>): Map<Long, List<Attachment>> {
        if (messageIds.isEmpty()) return emptyMap()
        return jdbc
            .sql("$COLS WHERE message_id = ANY(:ids) AND status = 1 ORDER BY id")
            .param("ids", messageIds.toLongArray())
            .query(::map)
            .list()
            .groupBy { it.messageId!! }
    }

    fun markReady(
        id: Long,
        width: Int?,
        height: Int?,
        thumbnailKey: String?,
        durationMs: Int?,
        waveform: List<Int>?,
    ) {
        jdbc.sql(
            """
            UPDATE attachments SET
                status = 1, ready_at = now(),
                width = :w, height = :h, thumbnail_key = :thumb,
                duration_ms = :dur,
                waveform = CAST(:wave AS smallint[])
            WHERE id = :id
            """
        )
            .param("w", width).param("h", height).param("thumb", thumbnailKey)
            .param("dur", durationMs)
            // Rendered as a Postgres array literal rather than bound as an array: the JDBC
            // driver has no smallint[] mapping for a Kotlin List<Int>.
            .param("wave", waveform?.joinToString(",", "{", "}"))
            .param("id", id)
            .update()
    }

    fun markFailed(id: Long) {
        jdbc.sql("UPDATE attachments SET status = 2 WHERE id = :id").param("id", id).update()
    }

    fun reclassify(id: Long, kind: AttachmentKind) {
        jdbc.sql("UPDATE attachments SET kind = :k WHERE id = :id")
            .param("k", kind.code.toInt()).param("id", id).update()
    }

    /**
     * Binds an attachment to a message, once.
     *
     * `message_id IS NULL` in the WHERE clause is what makes it once: a second attempt matches
     * no rows, so the same upload can't be re-sent into a different conversation.
     */
    fun claim(id: Long, messageId: Long, channelId: Long): Boolean = jdbc
        .sql(
            """
            UPDATE attachments SET message_id = :m, channel_id = :c
            WHERE id = :id AND message_id IS NULL AND status = 1
            """
        )
        .param("m", messageId).param("c", channelId).param("id", id)
        .update() == 1

    /** Uploads that were never attached to anything. Reaped after a grace period. */
    fun findOrphaned(olderThan: Instant, limit: Int = 500): List<Attachment> = jdbc
        .sql("$COLS WHERE message_id IS NULL AND created_at < :cutoff ORDER BY created_at LIMIT :limit")
        .param("cutoff", Timestamp.from(olderThan))
        .param("limit", limit)
        .query(::map)
        .list()

    fun delete(id: Long) {
        jdbc.sql("DELETE FROM attachments WHERE id = :id").param("id", id).update()
    }

    private companion object {
        const val COLS = """
            SELECT id, uploader_id, message_id, object_key, filename, content_type, size_bytes,
                   kind, status, width, height, thumbnail_key, duration_ms, waveform
            FROM attachments
        """

        fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): Attachment {
            val raw = rs.getArray("waveform")
            val peaks = if (raw == null) emptyList() else {
                @Suppress("UNCHECKED_CAST")
                (raw.array as Array<Any?>).mapNotNull { (it as? Number)?.toInt() }
            }
            return Attachment(
                id = rs.getLong("id"),
                uploaderId = rs.getLong("uploader_id"),
                messageId = rs.getObject("message_id") as Long?,
                objectKey = rs.getString("object_key"),
                filename = rs.getString("filename"),
                contentType = rs.getString("content_type"),
                sizeBytes = rs.getLong("size_bytes"),
                kind = AttachmentKind.ofCode(rs.getShort("kind")),
                status = AttachmentStatus.ofCode(rs.getShort("status")),
                width = rs.getObject("width") as Int?,
                height = rs.getObject("height") as Int?,
                thumbnailKey = rs.getString("thumbnail_key"),
                durationMs = rs.getObject("duration_ms") as Int?,
                waveform = peaks,
            )
        }
    }
}
