package app.singular.guild

import app.singular.audit.AuditLog
import app.singular.channel.ChannelRepository
import app.singular.core.Conflict
import app.singular.core.Forbidden
import app.singular.core.InvalidInput
import app.singular.core.NotFound
import app.singular.core.Snowflake
import app.singular.domain.AuditAction
import app.singular.domain.ChannelType
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import kotlin.random.Random

@Service
class GuildService(
    private val guilds: GuildRepository,
    private val channels: ChannelRepository,
    private val engine: PermissionEngine,
    private val snowflake: Snowflake,
    private val audit: AuditLog,
) {

    // -- Creating ------------------------------------------------------------

    /**
     * Creates a server, its @everyone role, and a #general channel, in one transaction.
     *
     * @everyone is given the **guild's own id** as its role id, which is how Discord does it.
     * That single choice removes a special case from every downstream lookup: the permission
     * engine never has to ask "is this the default role", it just resolves an ordinary row.
     */
    @Transactional
    fun createGuild(ownerId: Long, name: String, iconKey: String?): Guild {
        val trimmed = name.trim()
        if (trimmed.length !in 2..100) throw InvalidInput("Server names are 2-100 characters.")

        val guildId = snowflake.next()
        guilds.insertGuild(guildId, trimmed, ownerId, iconKey)

        guilds.insertRole(
            id = guildId,                       // @everyone shares the guild's id
            guildId = guildId,
            name = "@everyone",
            color = null,
            position = 0,                       // always the floor
            permissions = Permissions.DEFAULT_EVERYONE,
            hoist = false,
            mentionable = false,
        )

        guilds.addMember(guildId, ownerId)

        val generalId = snowflake.next()
        channels.insertGuildChannel(
            id = generalId,
            guildId = guildId,
            type = ChannelType.GUILD_TEXT,
            name = "general",
            parentId = null,
            position = 0,
        )

        audit.record(ownerId, AuditAction.GUILD_CREATE, targetId = guildId,
            changes = mapOf("name" to trimmed))

        return guilds.findGuild(guildId) ?: error("Guild $guildId vanished after insert")
    }

    fun listForUser(userId: Long): List<Guild> = guilds.guildsForUser(userId)

    // -- Membership context --------------------------------------------------

    /**
     * Loads everything the permission engine needs for one person.
     *
     * Throws if they aren't a member: "not in this server" and "in it with no permissions" are
     * different answers, and conflating them leaks the existence of servers you can't see.
     */
    fun contextOf(guildId: Long, userId: Long): MemberContext {
        val guild = guilds.findGuild(guildId) ?: throw NotFound("Server")
        guilds.findMember(guildId, userId) ?: throw Forbidden("that server")
        return MemberContext(
            userId = userId,
            guildId = guildId,
            guildOwnerId = guild.ownerId,
            roles = guilds.rolesForMember(guildId, userId),
        )
    }

    fun require(guildId: Long, userId: Long, flag: Permission): MemberContext {
        val ctx = contextOf(guildId, userId)
        if (!engine.basePermissions(ctx).allows(flag)) {
            throw Forbidden(flag.label.lowercase())
        }
        return ctx
    }

    /** Channel-scoped check — the one that actually gates reading and posting. */
    fun requireInChannel(channelId: Long, guildId: Long, userId: Long, flag: Permission): MemberContext {
        val ctx = contextOf(guildId, userId)
        val overwrites = guilds.overwritesFor(channelId)
        if (!engine.can(ctx, overwrites, flag)) throw Forbidden(flag.label.lowercase())
        return ctx
    }

    fun permissionsIn(channelId: Long, guildId: Long, userId: Long): Permissions =
        engine.permissionsIn(contextOf(guildId, userId), guilds.overwritesFor(channelId))

    // -- Roles ---------------------------------------------------------------

    @Transactional
    fun createRole(guildId: Long, actorId: Long, name: String, color: Int?): Role {
        val actor = require(guildId, actorId, Permission.MANAGE_ROLES)

        val id = snowflake.next()
        // New roles start directly below the creator's highest, never above it — otherwise
        // MANAGE_ROLES silently becomes "promote yourself".
        val ceiling = actor.highestRole?.position ?: 1
        val position = minOf(guilds.nextRolePosition(guildId), maxOf(ceiling - 1, 1))

        guilds.insertRole(id, guildId, name.trim(), color, position, Permissions.NONE, false, false)
        audit.record(actorId, AuditAction.ROLE_CREATE, targetId = id,
            changes = mapOf("guildId" to guildId.toString(), "name" to name))

        return guilds.findRole(id) ?: error("Role $id vanished after insert")
    }

    @Transactional
    fun updateRole(
        roleId: Long,
        actorId: Long,
        name: String?,
        color: Int?,
        permissions: Permissions?,
        hoist: Boolean?,
        mentionable: Boolean?,
    ): Role {
        val role = guilds.findRole(roleId) ?: throw NotFound("Role")
        val actor = contextOf(role.guildId, actorId)

        if (!engine.canManageRole(actor, role)) {
            throw Forbidden("that role — it is at or above your highest one")
        }

        // Nobody may grant a permission they don't hold themselves. Without this, anyone with
        // MANAGE_ROLES could mint a role with ADMINISTRATOR and hand it to themselves, which
        // makes the whole hierarchy decorative.
        if (permissions != null && !actor.isOwner) {
            val own = engine.basePermissions(actor)
            val escalation = permissions without own
            if (!escalation.isEmpty) {
                throw Forbidden("permissions you don't have yourself")
            }
        }

        guilds.updateRole(roleId, name?.trim(), color, permissions, hoist, mentionable)
        audit.record(actorId, AuditAction.ROLE_UPDATE, targetId = roleId)
        return guilds.findRole(roleId) ?: error("Role $roleId vanished after update")
    }

    @Transactional
    fun deleteRole(roleId: Long, actorId: Long): Boolean {
        val role = guilds.findRole(roleId) ?: throw NotFound("Role")
        if (role.id == role.guildId) throw InvalidInput("@everyone can't be deleted.")

        val actor = contextOf(role.guildId, actorId)
        if (!engine.canManageRole(actor, role)) throw Forbidden("that role")

        audit.record(actorId, AuditAction.ROLE_DELETE, targetId = roleId)
        return guilds.deleteRole(roleId)
    }

    @Transactional
    fun assignRole(guildId: Long, targetUserId: Long, roleId: Long, actorId: Long): Boolean {
        val role = guilds.findRole(roleId) ?: throw NotFound("Role")
        if (role.guildId != guildId) throw InvalidInput("That role belongs to another server.")

        val actor = contextOf(guildId, actorId)
        val target = contextOf(guildId, targetUserId)
        if (!engine.canAssignRole(actor, target, role)) throw Forbidden("that role")

        audit.record(actorId, AuditAction.ROLE_GRANT, targetId = targetUserId,
            changes = mapOf("roleId" to roleId.toString()))
        return guilds.assignRole(guildId, targetUserId, roleId)
    }

    @Transactional
    fun unassignRole(guildId: Long, targetUserId: Long, roleId: Long, actorId: Long): Boolean {
        val role = guilds.findRole(roleId) ?: throw NotFound("Role")
        val actor = contextOf(guildId, actorId)
        val target = contextOf(guildId, targetUserId)
        if (!engine.canAssignRole(actor, target, role)) throw Forbidden("that role")
        return guilds.unassignRole(guildId, targetUserId, roleId)
    }

    // -- Nicknames (feature 14) ----------------------------------------------

    @Transactional
    fun setNickname(guildId: Long, targetUserId: Long, actorId: Long, nickname: String?): Boolean {
        val actor = contextOf(guildId, actorId)

        if (targetUserId == actorId) {
            if (!engine.basePermissions(actor).allows(Permission.CHANGE_NICKNAME)) {
                throw Forbidden("changing your nickname here")
            }
        } else {
            if (!engine.basePermissions(actor).allows(Permission.MANAGE_NICKNAMES)) {
                throw Forbidden("changing other people's nicknames")
            }
            // Renaming someone who outranks you is a way to impersonate or mock them from
            // below, so the hierarchy applies here as much as it does to kicks.
            if (!engine.outranks(actor, contextOf(guildId, targetUserId))) {
                throw Forbidden("that member — they outrank you")
            }
        }

        if (nickname != null && nickname.trim().length !in 1..32) {
            throw InvalidInput("Nicknames are 1-32 characters.")
        }
        return guilds.setNickname(guildId, targetUserId, nickname)
    }

    // -- Moderation ----------------------------------------------------------

    @Transactional
    fun kick(guildId: Long, targetUserId: Long, actorId: Long): Boolean {
        val actor = require(guildId, actorId, Permission.KICK_MEMBERS)
        val target = contextOf(guildId, targetUserId)
        if (!engine.outranks(actor, target)) throw Forbidden("that member — they outrank you")

        audit.record(actorId, AuditAction.MEMBER_KICK, targetId = targetUserId,
            changes = mapOf("guildId" to guildId.toString()))
        return guilds.removeMember(guildId, targetUserId)
    }

    @Transactional
    fun timeout(guildId: Long, targetUserId: Long, actorId: Long, until: Instant?) {
        val actor = require(guildId, actorId, Permission.MODERATE_MEMBERS)
        val target = contextOf(guildId, targetUserId)
        if (!engine.outranks(actor, target)) throw Forbidden("that member — they outrank you")

        guilds.setTimeout(guildId, targetUserId, until)
        audit.record(actorId, AuditAction.MEMBER_TIMEOUT, targetId = targetUserId,
            changes = mapOf("until" to (until?.toString() ?: "cleared")))
    }

    @Transactional
    fun leave(guildId: Long, userId: Long): Boolean {
        val guild = guilds.findGuild(guildId) ?: throw NotFound("Server")
        // The owner leaving would orphan the server with nobody able to administer it.
        // Transferring ownership first is the correct flow.
        if (guild.ownerId == userId) {
            throw Conflict("Transfer ownership before leaving your own server.")
        }
        return guilds.removeMember(guildId, userId)
    }

    // -- Invites -------------------------------------------------------------

    @Transactional
    fun createInvite(
        guildId: Long,
        actorId: Long,
        channelId: Long?,
        maxUses: Int?,
        expiresAt: Instant?,
    ): Invite {
        require(guildId, actorId, Permission.CREATE_INVITE)
        val code = generateInviteCode()
        guilds.createInvite(code, guildId, channelId, actorId, maxUses, expiresAt)
        return guilds.findInvite(code) ?: error("Invite $code vanished after insert")
    }

    @Transactional
    fun redeemInvite(code: String, userId: Long): Guild {
        val invite = guilds.findInvite(code) ?: throw NotFound("Invite")

        // Already a member: succeed quietly rather than burning a use. Clicking an invite for a
        // server you're already in should just take you there.
        if (guilds.findMember(invite.guildId, userId) != null) {
            return guilds.findGuild(invite.guildId) ?: throw NotFound("Server")
        }

        if (!guilds.consumeInvite(code)) {
            throw InvalidInput("That invite has expired or run out of uses.")
        }
        guilds.addMember(invite.guildId, userId)
        audit.record(userId, AuditAction.MEMBER_JOIN, targetId = invite.guildId,
            changes = mapOf("invite" to code))

        return guilds.findGuild(invite.guildId) ?: throw NotFound("Server")
    }

    /**
     * Eight characters from an unambiguous alphabet.
     *
     * No 0/O, 1/l/I: invite codes get read aloud and typed by hand, and a code nobody can
     * transcribe is a broken invite. ~2.8e12 combinations, and codes are looked up by primary
     * key, so collisions are caught by the insert rather than guessed at.
     */
    private fun generateInviteCode(): String =
        (1..8).map { INVITE_ALPHABET[Random.nextInt(INVITE_ALPHABET.length)] }.joinToString("")

    private companion object {
        const val INVITE_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    }
}
