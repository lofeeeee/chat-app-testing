package app.singular.user

import app.singular.domain.User
import app.singular.domain.UserCredentials
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant

@Repository
class UserRepository(private val jdbc: JdbcClient) {

    fun findById(id: Long): User? = jdbc
        .sql("$SELECT_COLS WHERE id = :id AND deleted_at IS NULL")
        .param("id", id)
        .query(::mapUser)
        .optional()
        .orElse(null)

    /**
     * Batch load for DataLoader. One query for N ids instead of N queries — the N+1 in a chat
     * schema (message -> author) is the single most expensive mistake you can make here, and it
     * is invisible until you have real traffic.
     */
    fun findAllById(ids: Collection<Long>): Map<Long, User> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc
            .sql("$SELECT_COLS WHERE id = ANY(:ids) AND deleted_at IS NULL")
            .param("ids", ids.toLongArray())
            .query(::mapUser)
            .list()
            .associateBy { it.id }
    }

    fun findByHandle(username: String, discriminator: Short): User? = jdbc
        .sql("$SELECT_COLS WHERE lower(username) = lower(:u) AND discriminator = :d AND deleted_at IS NULL")
        .param("u", username)
        .param("d", discriminator.toInt())
        .query(::mapUser)
        .optional()
        .orElse(null)

    fun findCredentialsByEmailIndex(blindIndex: ByteArray): UserCredentials? = jdbc
        .sql("SELECT id, password_hash FROM users WHERE email_bidx = :bidx AND deleted_at IS NULL")
        .param("bidx", blindIndex)
        .query { rs, _ -> UserCredentials(rs.getLong("id"), rs.getString("password_hash")) }
        .optional()
        .orElse(null)

    fun emailIndexExists(blindIndex: ByteArray): Boolean = jdbc
        .sql("SELECT 1 FROM users WHERE email_bidx = :bidx")
        .param("bidx", blindIndex)
        .query(Int::class.java)
        .optional()
        .isPresent

    /**
     * Attempts one (username, discriminator) pair.
     *
     * Returns false when the unique index rejects it, which is how concurrent registrations of
     * the same name are arbitrated — the database decides, not a read-then-write race.
     */
    fun tryInsert(
        id: Long,
        username: String,
        discriminator: Short,
        displayName: String?,
        emailEnc: ByteArray,
        emailBidx: ByteArray,
        passwordHash: String,
    ): Boolean {
        val rows = jdbc
            .sql(
                """
                INSERT INTO users (id, username, discriminator, display_name,
                                   email_enc, email_bidx, password_hash)
                VALUES (:id, :username, :disc, :displayName, :emailEnc, :emailBidx, :hash)
                ON CONFLICT DO NOTHING
                """
            )
            .param("id", id)
            .param("username", username)
            .param("disc", discriminator.toInt())
            .param("displayName", displayName)
            .param("emailEnc", emailEnc)
            .param("emailBidx", emailBidx)
            .param("hash", passwordHash)
            .update()
        return rows == 1
    }

    /** Every discriminator currently in use for this username. At most 9,999 smallints. */
    fun takenDiscriminators(username: String): Set<Short> = jdbc
        .sql("SELECT discriminator FROM users WHERE lower(username) = lower(:u)")
        .param("u", username)
        .query(Short::class.java)
        .list()
        .toSet()

    /**
     * Pairs released within the quarantine window and therefore not reissuable.
     *
     * Without this, someone watching for a rename can grab the freed handle immediately and
     * impersonate its previous owner — the failure mode that eventually killed the scheme at
     * Discord.
     */
    fun quarantinedDiscriminators(username: String, since: Instant): Set<Short> = jdbc
        .sql(
            """
            SELECT discriminator FROM handle_quarantine
            WHERE username_lower = lower(:u) AND released_at > :since
            """
        )
        .param("u", username)
        .param("since", java.sql.Timestamp.from(since))
        .query(Short::class.java)
        .list()
        .toSet()

    /**
     * Profile customisation (feature 13).
     *
     * COALESCE per field so a client changing only the banner doesn't have to resend the bio
     * and risk clobbering an edit made on another device a second earlier.
     */
    fun updateProfile(
        userId: Long,
        displayName: String?,
        avatarKey: String?,
        bannerKey: String?,
        borderKey: String?,
        bio: String?,
        pronouns: String?,
        accentColor: Int?,
    ) {
        jdbc.sql(
            """
            UPDATE users SET
                display_name = COALESCE(:displayName, display_name),
                avatar_key   = COALESCE(:avatar, avatar_key),
                banner_key   = COALESCE(:banner, banner_key),
                border_key   = COALESCE(:border, border_key),
                bio          = COALESCE(:bio, bio),
                pronouns     = COALESCE(:pronouns, pronouns),
                accent_color = COALESCE(:accent, accent_color),
                updated_at   = now()
            WHERE id = :id
            """
        )
            .param("displayName", displayName?.trim()?.ifEmpty { null })
            .param("avatar", avatarKey).param("banner", bannerKey).param("border", borderKey)
            .param("bio", bio?.take(512)).param("pronouns", pronouns?.take(40))
            .param("accent", accentColor).param("id", userId)
            .update()
    }

    fun updatePasswordHash(userId: Long, hash: String) {
        jdbc.sql("UPDATE users SET password_hash = :h, updated_at = now() WHERE id = :id")
            .param("h", hash)
            .param("id", userId)
            .update()
    }

    private companion object {
        const val SELECT_COLS = """
            SELECT id, username, discriminator, display_name, avatar_key, banner_key,
                   bio, accent_color, border_key, pronouns, created_at
            FROM users
        """

        fun mapUser(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = User(
            id = rs.getLong("id"),
            username = rs.getString("username"),
            discriminator = rs.getShort("discriminator"),
            displayName = rs.getString("display_name"),
            avatarKey = rs.getString("avatar_key"),
            bannerKey = rs.getString("banner_key"),
            bio = rs.getString("bio"),
            accentColor = rs.getObject("accent_color") as Int?,
            borderKey = rs.getString("border_key"),
            pronouns = rs.getString("pronouns"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
}
