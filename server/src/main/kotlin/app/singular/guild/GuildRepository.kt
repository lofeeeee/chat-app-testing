package app.singular.guild

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

data class Guild(
    val id: Long,
    val name: String,
    val iconKey: String?,
    val bannerKey: String?,
    val description: String?,
    val ownerId: Long,
    val systemChannelId: Long?,
    val requires2faForModeration: Boolean,
    val createdAt: Instant,
)

data class GuildMember(
    val guildId: Long,
    val userId: Long,
    val nickname: String?,
    val avatarKey: String?,
    val joinedAt: Instant,
    val timedOutUntil: Instant?,
) {
    val isTimedOut: Boolean get() = timedOutUntil?.isAfter(Instant.now()) == true
}

data class Invite(
    val code: String,
    val guildId: Long,
    val channelId: Long?,
    val inviterId: Long?,
    val uses: Int,
    val maxUses: Int?,
    val expiresAt: Instant?,
)

@Repository
class GuildRepository(private val jdbc: JdbcClient) {

    // -- Guilds --------------------------------------------------------------

    fun insertGuild(id: Long, name: String, ownerId: Long, iconKey: String?) {
        jdbc.sql(
            """
            INSERT INTO guilds (id, name, owner_id, icon_key)
            VALUES (:id, :name, :owner, :icon)
            """
        )
            .param("id", id).param("name", name).param("owner", ownerId).param("icon", iconKey)
            .update()
    }

    fun findGuild(id: Long): Guild? = jdbc
        .sql("$GUILD_COLS WHERE id = :id AND deleted_at IS NULL")
        .param("id", id)
        .query(::mapGuild)
        .optional()
        .orElse(null)

    fun guildsForUser(userId: Long): List<Guild> = jdbc
        .sql(
            """
            SELECT g.id, g.name, g.icon_key, g.banner_key, g.description, g.owner_id,
                   g.system_channel_id, g.require_2fa_for_moderation, g.created_at
            FROM guilds g
            JOIN guild_members m ON m.guild_id = g.id
            WHERE m.user_id = :u AND g.deleted_at IS NULL
            ORDER BY m.joined_at
            """
        )
        .param("u", userId)
        .query(::mapGuild)
        .list()

    fun updateGuild(id: Long, name: String?, iconKey: String?, description: String?) {
        // COALESCE so a caller changing only the icon doesn't have to resend the name and
        // risk clobbering a concurrent rename.
        jdbc.sql(
            """
            UPDATE guilds SET
                name        = COALESCE(:name, name),
                icon_key    = COALESCE(:icon, icon_key),
                description = COALESCE(:desc, description)
            WHERE id = :id
            """
        )
            .param("name", name).param("icon", iconKey).param("desc", description).param("id", id)
            .update()
    }

    fun softDeleteGuild(id: Long) {
        jdbc.sql("UPDATE guilds SET deleted_at = now() WHERE id = :id")
            .param("id", id).update()
    }

    // -- Members -------------------------------------------------------------

    fun addMember(guildId: Long, userId: Long): Boolean = jdbc
        .sql(
            """
            INSERT INTO guild_members (guild_id, user_id) VALUES (:g, :u)
            ON CONFLICT DO NOTHING
            """
        )
        .param("g", guildId).param("u", userId)
        .update() == 1

    fun removeMember(guildId: Long, userId: Long): Boolean = jdbc
        .sql("DELETE FROM guild_members WHERE guild_id = :g AND user_id = :u")
        .param("g", guildId).param("u", userId)
        .update() == 1

    fun findMember(guildId: Long, userId: Long): GuildMember? = jdbc
        .sql("$MEMBER_COLS WHERE guild_id = :g AND user_id = :u")
        .param("g", guildId).param("u", userId)
        .query(::mapMember)
        .optional()
        .orElse(null)

    fun membersOf(guildId: Long, limit: Int = 200): List<GuildMember> = jdbc
        .sql("$MEMBER_COLS WHERE guild_id = :g ORDER BY joined_at LIMIT :limit")
        .param("g", guildId).param("limit", limit)
        .query(::mapMember)
        .list()

    /** Feature 14. Null clears it and falls back to the display name. */
    fun setNickname(guildId: Long, userId: Long, nickname: String?): Boolean = jdbc
        .sql("UPDATE guild_members SET nickname = :n WHERE guild_id = :g AND user_id = :u")
        .param("n", nickname?.trim()?.ifEmpty { null })
        .param("g", guildId).param("u", userId)
        .update() == 1

    fun setTimeout(guildId: Long, userId: Long, until: Instant?) {
        jdbc.sql("UPDATE guild_members SET timed_out_until = :t WHERE guild_id = :g AND user_id = :u")
            .param("t", until?.let(Timestamp::from))
            .param("g", guildId).param("u", userId)
            .update()
    }

    // -- Roles ---------------------------------------------------------------

    fun insertRole(
        id: Long,
        guildId: Long,
        name: String,
        color: Int?,
        position: Int,
        permissions: Permissions,
        hoist: Boolean,
        mentionable: Boolean,
    ) {
        jdbc.sql(
            """
            INSERT INTO roles (id, guild_id, name, color, position, permissions, hoist, mentionable)
            VALUES (:id, :g, :name, :color, :pos, CAST(:perms AS bit(128)), :hoist, :mentionable)
            """
        )
            .param("id", id).param("g", guildId).param("name", name).param("color", color)
            .param("pos", position).param("perms", permissions.toBitString())
            .param("hoist", hoist).param("mentionable", mentionable)
            .update()
    }

    fun rolesOf(guildId: Long): List<Role> = jdbc
        .sql("$ROLE_COLS WHERE guild_id = :g ORDER BY position DESC, id DESC")
        .param("g", guildId)
        .query(::mapRole)
        .list()

    fun findRole(id: Long): Role? = jdbc
        .sql("$ROLE_COLS WHERE id = :id")
        .param("id", id)
        .query(::mapRole)
        .optional()
        .orElse(null)

    /** Every role a member holds, @everyone included — that role's id equals the guild id. */
    fun rolesForMember(guildId: Long, userId: Long): List<Role> = jdbc
        .sql(
            """
            SELECT r.id, r.guild_id, r.name, r.color, r.icon_key, r.position,
                   r.permissions::text AS permissions, r.hoist, r.mentionable, r.managed_by
            FROM roles r
            WHERE r.guild_id = :g
              AND (r.id = :g OR r.id IN (
                    SELECT role_id FROM member_roles WHERE guild_id = :g AND user_id = :u))
            ORDER BY r.position DESC, r.id DESC
            """
        )
        .param("g", guildId).param("u", userId)
        .query(::mapRole)
        .list()

    fun updateRole(
        id: Long,
        name: String?,
        color: Int?,
        permissions: Permissions?,
        hoist: Boolean?,
        mentionable: Boolean?,
    ) {
        jdbc.sql(
            """
            UPDATE roles SET
                name        = COALESCE(:name, name),
                color       = COALESCE(:color, color),
                permissions = COALESCE(CAST(:perms AS bit(128)), permissions),
                hoist       = COALESCE(:hoist, hoist),
                mentionable = COALESCE(:mentionable, mentionable)
            WHERE id = :id
            """
        )
            .param("name", name).param("color", color)
            .param("perms", permissions?.toBitString())
            .param("hoist", hoist).param("mentionable", mentionable).param("id", id)
            .update()
    }

    fun deleteRole(id: Long): Boolean = jdbc
        .sql("DELETE FROM roles WHERE id = :id AND id <> guild_id")   // @everyone is undeletable
        .param("id", id)
        .update() == 1

    fun assignRole(guildId: Long, userId: Long, roleId: Long): Boolean = jdbc
        .sql(
            """
            INSERT INTO member_roles (guild_id, user_id, role_id) VALUES (:g, :u, :r)
            ON CONFLICT DO NOTHING
            """
        )
        .param("g", guildId).param("u", userId).param("r", roleId)
        .update() == 1

    fun unassignRole(guildId: Long, userId: Long, roleId: Long): Boolean = jdbc
        .sql("DELETE FROM member_roles WHERE guild_id = :g AND user_id = :u AND role_id = :r")
        .param("g", guildId).param("u", userId).param("r", roleId)
        .update() == 1

    fun nextRolePosition(guildId: Long): Int = jdbc
        .sql("SELECT COALESCE(MAX(position), 0) + 1 FROM roles WHERE guild_id = :g")
        .param("g", guildId)
        .query(Int::class.java)
        .optional()
        .orElse(1)

    // -- Overwrites ----------------------------------------------------------

    fun overwritesFor(channelId: Long): List<Overwrite> = jdbc
        .sql(
            """
            SELECT target_id, target_type, allow::text AS allow, deny::text AS deny
            FROM channel_overwrites WHERE channel_id = :c
            """
        )
        .param("c", channelId)
        .query { rs, _ ->
            Overwrite(
                targetId = rs.getLong("target_id"),
                targetType = rs.getShort("target_type"),
                allow = Permissions.fromBitString(rs.getString("allow")),
                deny = Permissions.fromBitString(rs.getString("deny")),
            )
        }
        .list()

    fun upsertOverwrite(
        channelId: Long,
        targetId: Long,
        targetType: Short,
        allow: Permissions,
        deny: Permissions,
    ) {
        jdbc.sql(
            """
            INSERT INTO channel_overwrites (channel_id, target_id, target_type, allow, deny)
            VALUES (:c, :t, :tt, CAST(:allow AS bit(128)), CAST(:deny AS bit(128)))
            ON CONFLICT (channel_id, target_id) DO UPDATE SET
                target_type = EXCLUDED.target_type,
                allow       = EXCLUDED.allow,
                deny        = EXCLUDED.deny
            """
        )
            .param("c", channelId).param("t", targetId).param("tt", targetType.toInt())
            .param("allow", allow.toBitString()).param("deny", deny.toBitString())
            .update()
    }

    fun deleteOverwrite(channelId: Long, targetId: Long): Boolean = jdbc
        .sql("DELETE FROM channel_overwrites WHERE channel_id = :c AND target_id = :t")
        .param("c", channelId).param("t", targetId)
        .update() == 1

    // -- Invites -------------------------------------------------------------

    fun createInvite(
        code: String,
        guildId: Long,
        channelId: Long?,
        inviterId: Long,
        maxUses: Int?,
        expiresAt: Instant?,
    ) {
        jdbc.sql(
            """
            INSERT INTO guild_invites (code, guild_id, channel_id, inviter_id, max_uses, expires_at)
            VALUES (:code, :g, :c, :i, :max, :exp)
            """
        )
            .param("code", code).param("g", guildId).param("c", channelId).param("i", inviterId)
            .param("max", maxUses).param("exp", expiresAt?.let(Timestamp::from))
            .update()
    }

    fun findInvite(code: String): Invite? = jdbc
        .sql(
            """
            SELECT code, guild_id, channel_id, inviter_id, uses, max_uses, expires_at
            FROM guild_invites WHERE code = :code
            """
        )
        .param("code", code)
        .query { rs, _ ->
            Invite(
                code = rs.getString("code"),
                guildId = rs.getLong("guild_id"),
                channelId = rs.getObject("channel_id") as Long?,
                inviterId = rs.getObject("inviter_id") as Long?,
                uses = rs.getInt("uses"),
                maxUses = rs.getObject("max_uses") as Int?,
                expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
            )
        }
        .optional()
        .orElse(null)

    /**
     * Consumes one use, atomically.
     *
     * Both limits are in the WHERE clause rather than checked first: a read-then-update would
     * let two people racing on the last use of an invite both get in.
     */
    fun consumeInvite(code: String): Boolean = jdbc
        .sql(
            """
            UPDATE guild_invites SET uses = uses + 1
            WHERE code = :code
              AND (expires_at IS NULL OR expires_at > now())
              AND (max_uses  IS NULL OR uses < max_uses)
            """
        )
        .param("code", code)
        .update() == 1

    fun invitesFor(guildId: Long): List<Invite> = jdbc
        .sql(
            """
            SELECT code, guild_id, channel_id, inviter_id, uses, max_uses, expires_at
            FROM guild_invites WHERE guild_id = :g ORDER BY created_at DESC
            """
        )
        .param("g", guildId)
        .query { rs, _ ->
            Invite(
                rs.getString("code"), rs.getLong("guild_id"),
                rs.getObject("channel_id") as Long?, rs.getObject("inviter_id") as Long?,
                rs.getInt("uses"), rs.getObject("max_uses") as Int?,
                rs.getTimestamp("expires_at")?.toInstant(),
            )
        }
        .list()

    fun deleteInvite(code: String): Boolean = jdbc
        .sql("DELETE FROM guild_invites WHERE code = :code")
        .param("code", code)
        .update() == 1

    private companion object {
        const val GUILD_COLS = """
            SELECT id, name, icon_key, banner_key, description, owner_id, system_channel_id,
                   require_2fa_for_moderation, created_at
            FROM guilds
        """
        const val MEMBER_COLS = """
            SELECT guild_id, user_id, nickname, avatar_key, joined_at, timed_out_until
            FROM guild_members
        """
        // permissions::text turns bit(128) into the '0101...' string BigInteger can parse.
        const val ROLE_COLS = """
            SELECT id, guild_id, name, color, icon_key, position,
                   permissions::text AS permissions, hoist, mentionable, managed_by
            FROM roles
        """

        fun mapGuild(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = Guild(
            id = rs.getLong("id"),
            name = rs.getString("name"),
            iconKey = rs.getString("icon_key"),
            bannerKey = rs.getString("banner_key"),
            description = rs.getString("description"),
            ownerId = rs.getLong("owner_id"),
            systemChannelId = rs.getObject("system_channel_id") as Long?,
            requires2faForModeration = rs.getBoolean("require_2fa_for_moderation"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )

        fun mapMember(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = GuildMember(
            guildId = rs.getLong("guild_id"),
            userId = rs.getLong("user_id"),
            nickname = rs.getString("nickname"),
            avatarKey = rs.getString("avatar_key"),
            joinedAt = rs.getTimestamp("joined_at").toInstant(),
            timedOutUntil = rs.getTimestamp("timed_out_until")?.toInstant(),
        )

        fun mapRole(rs: ResultSet, @Suppress("UNUSED_PARAMETER") n: Int) = Role(
            id = rs.getLong("id"),
            guildId = rs.getLong("guild_id"),
            name = rs.getString("name"),
            color = rs.getObject("color") as Int?,
            iconKey = rs.getString("icon_key"),
            position = rs.getInt("position"),
            permissions = Permissions.fromBitString(rs.getString("permissions")),
            hoist = rs.getBoolean("hoist"),
            mentionable = rs.getBoolean("mentionable"),
            managedBy = rs.getObject("managed_by") as Long?,
        )
    }
}
