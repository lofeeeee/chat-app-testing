package app.singular.story

import app.singular.config.SingularProperties
import app.singular.core.Forbidden
import app.singular.core.InvalidInput
import app.singular.core.NotFound
import app.singular.core.Snowflake
import app.singular.social.SocialRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * Who can see a story.
 *
 * `ALL` means everyone you share a conversation with — not literally everyone, which would
 * make a story a public post rather than something you show your contacts.
 */
data class Audience(val mode: String = "all", val userIds: List<Long> = emptyList()) {
    fun allows(viewerId: Long, authorId: Long): Boolean = when (mode) {
        "except" -> viewerId !in userIds
        "only" -> viewerId in userIds
        else -> true
    } || viewerId == authorId    // you always see your own
}

data class Story(
    val id: Long,
    val authorId: Long,
    val attachmentId: Long?,
    val background: String?,
    /** Raw JSON. Composited by the client, never interpreted here. */
    val overlaysJson: String,
    val audience: Audience,
    val createdAt: Instant,
    val expiresAt: Instant,
) {
    val isLive: Boolean get() = expiresAt.isAfter(Instant.now())
}

@Repository
class StoryRepository(private val jdbc: JdbcClient, private val json: ObjectMapper) {

    fun insert(
        id: Long,
        authorId: Long,
        attachmentId: Long?,
        background: String?,
        overlaysJson: String,
        audienceJson: String,
        expiresAt: Instant,
    ) {
        jdbc.sql(
            """
            INSERT INTO stories (id, author_id, attachment_id, background, overlays, audience, expires_at)
            VALUES (:id, :a, :att, :bg, CAST(:ov AS jsonb), CAST(:aud AS jsonb), :exp)
            """
        )
            .param("id", id).param("a", authorId).param("att", attachmentId)
            .param("bg", background).param("ov", overlaysJson).param("aud", audienceJson)
            .param("exp", Timestamp.from(expiresAt))
            .update()
    }

    fun find(id: Long): Story? = jdbc
        .sql("$COLS WHERE id = :id AND deleted_at IS NULL")
        .param("id", id)
        .query(::map)
        .optional()
        .orElse(null)

    /** Everything still live from a set of authors, newest first. */
    fun liveFrom(authorIds: Collection<Long>): List<Story> {
        if (authorIds.isEmpty()) return emptyList()
        return jdbc
            .sql(
                """
                $COLS WHERE author_id = ANY(:ids)
                  AND deleted_at IS NULL AND expires_at > now()
                ORDER BY created_at DESC
                """
            )
            .param("ids", authorIds.toLongArray())
            .query(::map)
            .list()
    }

    fun recordView(storyId: Long, viewerId: Long) {
        jdbc.sql(
            """
            INSERT INTO story_views (story_id, viewer_id) VALUES (:s, :v)
            ON CONFLICT DO NOTHING
            """
        )
            .param("s", storyId).param("v", viewerId)
            .update()
    }

    fun viewerIds(storyId: Long): List<Long> = jdbc
        .sql("SELECT viewer_id FROM story_views WHERE story_id = :s ORDER BY viewed_at DESC")
        .param("s", storyId)
        .query(Long::class.java)
        .list()

    fun viewCount(storyId: Long): Int = jdbc
        .sql("SELECT count(*) FROM story_views WHERE story_id = :s")
        .param("s", storyId)
        .query(Int::class.java)
        .optional()
        .orElse(0)

    fun softDelete(id: Long, authorId: Long): Boolean = jdbc
        .sql("UPDATE stories SET deleted_at = now() WHERE id = :id AND author_id = :a")
        .param("id", id).param("a", authorId)
        .update() == 1

    fun expiredKeys(limit: Int = 500): List<Long> = jdbc
        .sql(
            """
            SELECT id FROM stories
            WHERE expires_at < now() AND deleted_at IS NULL
            ORDER BY expires_at LIMIT :limit
            """
        )
        .param("limit", limit)
        .query(Long::class.java)
        .list()

    fun hardDelete(id: Long) {
        jdbc.sql("DELETE FROM stories WHERE id = :id").param("id", id).update()
    }

    private inner class Mapper

    private fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int): Story {
        val audienceRaw = rs.getString("audience")
        val audience = runCatching {
            val node = json.readTree(audienceRaw)
            Audience(
                mode = node.path("mode").asText("all"),
                userIds = node.path("userIds").mapNotNull { it.asText().toLongOrNull() },
            )
        // A malformed audience must fail closed. Defaulting to "all" on a parse error would
        // publish a story its author had restricted.
        }.getOrDefault(Audience(mode = "only", userIds = emptyList()))

        return Story(
            id = rs.getLong("id"),
            authorId = rs.getLong("author_id"),
            attachmentId = rs.getObject("attachment_id") as Long?,
            background = rs.getString("background"),
            overlaysJson = rs.getString("overlays") ?: "[]",
            audience = audience,
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
        )
    }

    private companion object {
        const val COLS = """
            SELECT id, author_id, attachment_id, background,
                   overlays::text AS overlays, audience::text AS audience,
                   created_at, expires_at
            FROM stories
        """
    }
}

/**
 * Stories — WhatsApp's Status, Instagram's Stories. Features 5 and 20.
 *
 * Two design decisions worth not re-litigating:
 *
 * **Overlays are data, not pixels.** Text, stickers, mentions, the music widget and location
 * pins are stored as a JSONB list of positioned elements and composited by the client. Baking
 * them into the image would make the story unrestylable, unlocalisable and unsearchable, and
 * would mean re-uploading to fix a typo.
 *
 * **Expiry is enforced on read, not by a job.** `expires_at` is in the query. The reaper only
 * reclaims storage; it is never what makes a story stop being visible, because a reaper that
 * falls behind would otherwise leave stories up past their day.
 */
@Service
class StoryService(
    private val stories: StoryRepository,
    private val social: SocialRepository,
    private val snowflake: Snowflake,
    private val json: ObjectMapper,
    private val props: SingularProperties,
) {

    @Transactional
    fun create(
        authorId: Long,
        attachmentId: Long?,
        background: String?,
        overlaysJson: String?,
        audienceMode: String?,
        audienceUserIds: List<Long>,
    ): Story {
        if (attachmentId == null && background == null) {
            throw InvalidInput("A story needs either media or a background.")
        }

        val overlays = overlaysJson?.takeIf { it.isNotBlank() } ?: "[]"
        // Parsed before storing so a broken client can't write JSON that every later read
        // trips over — failing here is one error, failing on read is an error per viewer.
        runCatching { json.readTree(overlays) }
            .getOrElse { throw InvalidInput("Overlays must be valid JSON.") }
        if (overlays.length > MAX_OVERLAY_BYTES) {
            throw InvalidInput("That's too many overlays for one story.")
        }

        val audience = json.writeValueAsString(
            mapOf(
                "mode" to (audienceMode?.lowercase() ?: "all"),
                "userIds" to audienceUserIds.map(Long::toString),
            )
        )

        val id = snowflake.next()
        stories.insert(
            id, authorId, attachmentId, background, overlays, audience,
            Instant.now().plus(props.media.storyTtl),
        )
        return stories.find(id) ?: error("Story $id vanished after insert")
    }

    /**
     * The story feed.
     *
     * Blocked authors are filtered here rather than at render time: a blocked person's story
     * should not appear in the tray at all, unlike a message in a shared channel which is
     * delivered and collapsed.
     */
    fun feedFor(viewerId: Long, authorIds: Collection<Long>): List<Story> {
        val blocked = social.blockedBy(viewerId)
        return stories.liveFrom(authorIds.filter { it !in blocked })
            .filter { it.audience.allows(viewerId, it.authorId) }
    }

    fun view(storyId: Long, viewerId: Long): Story {
        val story = stories.find(storyId) ?: throw NotFound("Story")
        if (!story.isLive) throw NotFound("Story")
        if (!story.audience.allows(viewerId, story.authorId)) throw Forbidden("that story")
        if (viewerId in social.blockedBy(viewerId)) throw Forbidden("that story")

        // Authors don't appear in their own viewer list — every story app works this way, and
        // seeing yourself there reads as a bug.
        if (viewerId != story.authorId) stories.recordView(storyId, viewerId)
        return story
    }

    fun viewers(storyId: Long, requesterId: Long): List<Long> {
        val story = stories.find(storyId) ?: throw NotFound("Story")
        // Only the author sees who watched. Exposing it to viewers would turn a story into a
        // record of who else was looking.
        if (story.authorId != requesterId) throw Forbidden("that story's viewers")
        return stories.viewerIds(storyId)
    }

    fun delete(storyId: Long, authorId: Long): Boolean = stories.softDelete(storyId, authorId)

    private companion object {
        const val MAX_OVERLAY_BYTES = 16 * 1024
    }
}
