package app.singular.social

import app.singular.domain.PresenceStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

/** What the client owns and the server only stores. */
data class UserSettings(
    val chatLayout: Int,
    val themePrimary: Int?,
    val themeSecondary: Int?,
    val themeDark: Boolean?,
)

/** The user's chosen status, as opposed to what others currently see. */
data class DesiredStatus(
    val status: PresenceStatus,
    val customText: String?,
    val customEmoji: String?,
    val expiresAt: Instant?,
)

@Repository
class SocialRepository(private val jdbc: JdbcClient) {

    // -- Blocking ------------------------------------------------------------

    fun block(blockerId: Long, blockedId: Long): Boolean = jdbc
        .sql(
            """
            INSERT INTO blocks (blocker_id, blocked_id) VALUES (:a, :b)
            ON CONFLICT DO NOTHING
            """
        )
        .param("a", blockerId)
        .param("b", blockedId)
        .update() == 1

    fun unblock(blockerId: Long, blockedId: Long): Boolean = jdbc
        .sql("DELETE FROM blocks WHERE blocker_id = :a AND blocked_id = :b")
        .param("a", blockerId)
        .param("b", blockedId)
        .update() == 1

    /**
     * Everyone this user has blocked.
     *
     * Loaded once per request and held in memory rather than joined into the message query:
     * a person's block list is tiny (tens of entries at most) and reading a channel needs it
     * for every row, so one small fetch beats a join that repeats per message.
     */
    fun blockedBy(blockerId: Long): Set<Long> = jdbc
        .sql("SELECT blocked_id FROM blocks WHERE blocker_id = :a")
        .param("a", blockerId)
        .query(Long::class.java)
        .list()
        .toSet()

    /** True if either side has blocked the other — used to refuse DM delivery. */
    fun blockExistsEitherWay(a: Long, b: Long): Boolean = jdbc
        .sql(
            """
            SELECT 1 FROM blocks
            WHERE (blocker_id = :a AND blocked_id = :b)
               OR (blocker_id = :b AND blocked_id = :a)
            LIMIT 1
            """
        )
        .param("a", a)
        .param("b", b)
        .query(Int::class.java)
        .optional()
        .isPresent

    // -- Muting --------------------------------------------------------------

    fun muteUser(userId: Long, mutedId: Long, until: Instant?) {
        jdbc.sql(
            """
            INSERT INTO user_mutes (user_id, muted_id, muted_until) VALUES (:u, :m, :t)
            ON CONFLICT (user_id, muted_id) DO UPDATE SET muted_until = EXCLUDED.muted_until
            """
        )
            .param("u", userId)
            .param("m", mutedId)
            .param("t", until?.let(Timestamp::from))
            .update()
    }

    fun unmuteUser(userId: Long, mutedId: Long): Boolean = jdbc
        .sql("DELETE FROM user_mutes WHERE user_id = :u AND muted_id = :m")
        .param("u", userId)
        .param("m", mutedId)
        .update() == 1

    fun mutedUsers(userId: Long): Set<Long> = jdbc
        .sql(
            """
            SELECT muted_id FROM user_mutes
            WHERE user_id = :u AND (muted_until IS NULL OR muted_until > now())
            """
        )
        .param("u", userId)
        .query(Long::class.java)
        .list()
        .toSet()

    fun muteChannel(channelId: Long, userId: Long, until: Instant?): Boolean = jdbc
        .sql(
            """
            UPDATE channel_members SET muted_until = :t
            WHERE channel_id = :c AND user_id = :u
            """
        )
        .param("t", until?.let(Timestamp::from))
        .param("c", channelId)
        .param("u", userId)
        .update() == 1

    fun mutedChannels(userId: Long): Set<Long> = jdbc
        .sql(
            """
            SELECT channel_id FROM channel_members
            WHERE user_id = :u AND muted_until IS NOT NULL AND muted_until > now()
            """
        )
        .param("u", userId)
        .query(Long::class.java)
        .list()
        .toSet()

    // -- Settings ------------------------------------------------------------

    fun settings(userId: Long): UserSettings = jdbc
        .sql(
            """
            SELECT chat_layout, theme_primary, theme_secondary, theme_dark
            FROM user_settings WHERE user_id = :u
            """
        )
        .param("u", userId)
        .query { rs, _ ->
            UserSettings(
                chatLayout = rs.getInt("chat_layout"),
                themePrimary = rs.getObject("theme_primary") as Int?,
                themeSecondary = rs.getObject("theme_secondary") as Int?,
                themeDark = rs.getObject("theme_dark") as Boolean?,
            )
        }
        .optional()
        // Never null: a user who has never opened settings still needs defaults, and returning
        // null here would push that branch into every caller.
        .orElse(UserSettings(chatLayout = 0, themePrimary = null, themeSecondary = null, themeDark = null))

    fun saveSettings(userId: Long, s: UserSettings) {
        jdbc.sql(
            """
            INSERT INTO user_settings (user_id, chat_layout, theme_primary, theme_secondary, theme_dark)
            VALUES (:u, :layout, :p, :s, :dark)
            ON CONFLICT (user_id) DO UPDATE SET
                chat_layout     = EXCLUDED.chat_layout,
                theme_primary   = EXCLUDED.theme_primary,
                theme_secondary = EXCLUDED.theme_secondary,
                theme_dark      = EXCLUDED.theme_dark,
                updated_at      = now()
            """
        )
            .param("u", userId)
            .param("layout", s.chatLayout)
            .param("p", s.themePrimary)
            .param("s", s.themeSecondary)
            .param("dark", s.themeDark)
            .update()
    }

    // -- Desired status ------------------------------------------------------

    fun desiredStatus(userId: Long): DesiredStatus = jdbc
        .sql(
            """
            SELECT desired_status, custom_status, custom_status_emoji, custom_status_expires_at
            FROM users WHERE id = :u
            """
        )
        .param("u", userId)
        .query { rs, _ ->
            val expiry = rs.getTimestamp("custom_status_expires_at")?.toInstant()
            val expired = expiry != null && expiry.isBefore(Instant.now())
            DesiredStatus(
                status = PresenceStatus.ofCode(rs.getShort("desired_status")),
                // Expiring in the read rather than with a sweeper job: a stale custom status is
                // only wrong when someone looks at it, so checking on read is both cheaper and
                // impossible to get out of sync.
                customText = if (expired) null else rs.getString("custom_status"),
                customEmoji = if (expired) null else rs.getString("custom_status_emoji"),
                expiresAt = expiry,
            )
        }
        .optional()
        .orElse(DesiredStatus(PresenceStatus.ONLINE, null, null, null))

    fun desiredStatusFor(userIds: Collection<Long>): Map<Long, DesiredStatus> {
        if (userIds.isEmpty()) return emptyMap()
        return jdbc
            .sql(
                """
                SELECT id, desired_status, custom_status, custom_status_emoji,
                       custom_status_expires_at
                FROM users WHERE id = ANY(:ids)
                """
            )
            .param("ids", userIds.toLongArray())
            .query { rs, _ ->
                val expiry = rs.getTimestamp("custom_status_expires_at")?.toInstant()
                val expired = expiry != null && expiry.isBefore(Instant.now())
                rs.getLong("id") to DesiredStatus(
                    status = PresenceStatus.ofCode(rs.getShort("desired_status")),
                    customText = if (expired) null else rs.getString("custom_status"),
                    customEmoji = if (expired) null else rs.getString("custom_status_emoji"),
                    expiresAt = expiry,
                )
            }
            .list()
            .toMap()
    }

    fun setDesiredStatus(userId: Long, status: PresenceStatus) {
        jdbc.sql("UPDATE users SET desired_status = :s, updated_at = now() WHERE id = :u")
            .param("s", status.code.toInt())
            .param("u", userId)
            .update()
    }

    fun setCustomStatus(userId: Long, text: String?, emoji: String?, expiresAt: Instant?) {
        jdbc.sql(
            """
            UPDATE users SET custom_status = :t, custom_status_emoji = :e,
                             custom_status_expires_at = :x, updated_at = now()
            WHERE id = :u
            """
        )
            .param("t", text?.take(128))
            .param("e", emoji?.take(16))
            .param("x", expiresAt?.let(Timestamp::from))
            .param("u", userId)
            .update()
    }
}
