package app.singular.message

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

enum class MentionType(val code: Short) {
    USER(0), ROLE(1), EVERYONE(2), HERE(3);

    companion object {
        fun ofCode(code: Short) = entries.firstOrNull { it.code == code } ?: USER
    }
}

data class Mention(val type: MentionType, val targetId: Long)

/**
 * Extracts mentions from a message body.
 *
 * The wire format is Discord's inline entity syntax — `<@123>` for a user, `<@&456>` for a
 * role, `<#789>` for a channel — kept inside the message text itself rather than split into a
 * parallel rich-text tree.
 *
 * Two reasons. It survives every edit and quote path unchanged, because the mention travels
 * with the text instead of alongside it. And the client renders it by substitution at display
 * time, so a user who changes their nickname is instantly re-rendered everywhere they were
 * ever mentioned — where a stored display string would freeze their old name into history.
 */
@Component
class MentionParser {

    fun parse(content: String): List<Mention> {
        val found = LinkedHashSet<Mention>()

        USER_PATTERN.findAll(content).forEach { m ->
            m.groupValues[1].toLongOrNull()?.let { found += Mention(MentionType.USER, it) }
        }
        ROLE_PATTERN.findAll(content).forEach { m ->
            m.groupValues[1].toLongOrNull()?.let { found += Mention(MentionType.ROLE, it) }
        }

        // @everyone and @here carry no target, so they're stored with id 0. They are also the
        // two that need a permission check before they fan out to anyone.
        if (EVERYONE_PATTERN.containsMatchIn(content)) found += Mention(MentionType.EVERYONE, 0)
        if (HERE_PATTERN.containsMatchIn(content)) found += Mention(MentionType.HERE, 0)

        return found.toList()
    }

    /** Channel references. Not notifications — just links the client turns into `#general`. */
    fun channelRefs(content: String): List<Long> =
        CHANNEL_PATTERN.findAll(content).mapNotNull { it.groupValues[1].toLongOrNull() }.toList()

    private companion object {
        val USER_PATTERN = Regex("""<@(\d{1,20})>""")
        val ROLE_PATTERN = Regex("""<@&(\d{1,20})>""")
        val CHANNEL_PATTERN = Regex("""<#(\d{1,20})>""")

        // Word-boundary anchored so "email@everyone.com" doesn't ping a whole server.
        val EVERYONE_PATTERN = Regex("""(^|\s)@everyone\b""")
        val HERE_PATTERN = Regex("""(^|\s)@here\b""")
    }
}

@Repository
class MentionRepository(private val jdbc: JdbcClient) {

    fun record(
        messageId: Long,
        channelId: Long,
        guildId: Long?,
        createdAt: Instant,
        mentions: List<Mention>,
    ) {
        mentions.forEach { mention ->
            jdbc.sql(
                """
                INSERT INTO message_mentions
                    (message_id, channel_id, guild_id, target_type, target_id, created_at)
                VALUES (:m, :c, :g, :tt, :ti, :at)
                ON CONFLICT DO NOTHING
                """
            )
                .param("m", messageId).param("c", channelId).param("g", guildId)
                .param("tt", mention.type.code.toInt()).param("ti", mention.targetId)
                .param("at", Timestamp.from(createdAt))
                .update()
        }
    }

    /**
     * The mentions inbox: message ids aimed at this person, newest first.
     *
     * Role mentions are included by resolving the roles they hold — someone mentioned via
     * `@moderators` should find it here exactly like a direct mention, which is the whole
     * point of mentioning a role.
     */
    fun inboxFor(userId: Long, limit: Int = 50): List<Long> = jdbc
        .sql(
            """
            SELECT DISTINCT m.message_id
            FROM message_mentions m
            WHERE (m.target_type = 0 AND m.target_id = :u)
               OR (m.target_type = 1 AND m.target_id IN (
                     SELECT role_id FROM member_roles WHERE user_id = :u))
            ORDER BY m.message_id DESC
            LIMIT :limit
            """
        )
        .param("u", userId)
        .param("limit", limit)
        .query(Long::class.java)
        .list()

    fun mentionsOf(messageIds: Collection<Long>): Map<Long, List<Mention>> {
        if (messageIds.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                SELECT message_id, target_type, target_id FROM message_mentions
                WHERE message_id = ANY(:ids)
                """
            )
            .param("ids", messageIds.toLongArray())
            .query { rs, _ ->
                rs.getLong("message_id") to
                    Mention(MentionType.ofCode(rs.getShort("target_type")), rs.getLong("target_id"))
            }
            .list()
            .groupBy({ it.first }, { it.second })
    }
}
