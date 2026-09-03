package app.singular.api

import app.singular.audit.AuditLog
import app.singular.channel.ChannelRepository
import app.singular.core.Forbidden
import app.singular.core.InvalidInput
import app.singular.core.NotFound
import app.singular.core.Snowflake
import app.singular.domain.AuditAction
import app.singular.domain.Channel
import app.singular.domain.ChannelType
import app.singular.domain.User
import app.singular.guild.FolderLayout
import app.singular.guild.FolderRepository
import app.singular.guild.Guild
import app.singular.guild.GuildFolder
import app.singular.guild.GuildMember
import app.singular.guild.GuildRepository
import app.singular.guild.GuildService
import app.singular.guild.Invite
import app.singular.guild.Permission
import app.singular.guild.PermissionEngine
import app.singular.guild.Permissions
import app.singular.guild.Role
import app.singular.security.requirePrincipal
import app.singular.user.UserRepository
import graphql.GraphQLContext
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.stereotype.Controller
import java.time.Instant

data class GuildFolderInput(
    val id: String,
    val name: String? = null,
    val color: Int? = null,
    val guildIds: List<String> = emptyList(),
)

data class PermissionFlagView(val name: String, val bit: Int, val label: String)

/** A member paired with the viewer, so nested resolvers know who is asking. */
data class GuildView(val guild: Guild, val viewerId: Long)

@Controller
class GuildController(
    private val service: GuildService,
    private val guilds: GuildRepository,
    private val channels: ChannelRepository,
    private val folders: FolderRepository,
    private val engine: PermissionEngine,
    private val users: UserRepository,
    private val snowflake: Snowflake,
    private val audit: AuditLog,
) {

    // -- Queries -------------------------------------------------------------

    @QueryMapping
    fun guilds(ctx: GraphQLContext): List<GuildView> {
        val me = ctx.requirePrincipal().userId
        return service.listForUser(me).map { GuildView(it, me) }
    }

    @QueryMapping
    fun guild(@Argument id: Long, ctx: GraphQLContext): GuildView {
        val me = ctx.requirePrincipal().userId
        // contextOf throws if you're not a member, which is what stops this doubling as a
        // probe for whether a given server id exists.
        service.contextOf(id, me)
        return GuildView(guilds.findGuild(id) ?: throw NotFound("Server"), me)
    }

    @QueryMapping
    fun guildMembers(
        @Argument guildId: Long,
        @Argument limit: Int?,
        ctx: GraphQLContext,
    ): List<GuildMember> {
        service.contextOf(guildId, ctx.requirePrincipal().userId)
        return guilds.membersOf(guildId, (limit ?: 100).coerceIn(1, 500))
    }

    @QueryMapping
    fun invites(@Argument guildId: Long, ctx: GraphQLContext): List<Invite> {
        service.require(guildId, ctx.requirePrincipal().userId, Permission.MANAGE_INVITES)
        return guilds.invitesFor(guildId)
    }

    @QueryMapping
    fun folders(ctx: GraphQLContext): FolderLayout =
        folders.load(ctx.requirePrincipal().userId)

    /** Lets a client build a permissions editor without hard-coding the bit numbers. */
    @QueryMapping
    fun permissionFlags(): List<PermissionFlagView> =
        Permission.entries.map { PermissionFlagView(it.name, it.bit, it.label) }

    // -- Mutations -----------------------------------------------------------

    @MutationMapping
    fun createGuild(
        @Argument name: String,
        @Argument iconKey: String?,
        ctx: GraphQLContext,
    ): GuildView {
        val me = ctx.requirePrincipal().userId
        return GuildView(service.createGuild(me, name, iconKey), me)
    }

    @MutationMapping
    fun updateGuild(
        @Argument id: Long,
        @Argument name: String?,
        @Argument iconKey: String?,
        @Argument description: String?,
        ctx: GraphQLContext,
    ): GuildView {
        val me = ctx.requirePrincipal().userId
        service.require(id, me, Permission.MANAGE_GUILD)
        guilds.updateGuild(id, name?.trim(), iconKey, description)
        audit.record(me, AuditAction.GUILD_UPDATE, targetId = id)
        return GuildView(guilds.findGuild(id) ?: throw NotFound("Server"), me)
    }

    @MutationMapping
    fun deleteGuild(@Argument id: Long, ctx: GraphQLContext): Boolean {
        val me = ctx.requirePrincipal().userId
        val guild = guilds.findGuild(id) ?: throw NotFound("Server")
        // Only the owner, never an administrator. Deleting a server is unrecoverable, and
        // ADMINISTRATOR is handed out far too freely to carry that.
        if (guild.ownerId != me) throw Forbidden("deleting this server")
        guilds.softDeleteGuild(id)
        audit.record(me, AuditAction.GUILD_DELETE, targetId = id)
        return true
    }

    @MutationMapping
    fun leaveGuild(@Argument id: Long, ctx: GraphQLContext): Boolean =
        service.leave(id, ctx.requirePrincipal().userId)

    @MutationMapping
    fun createGuildChannel(
        @Argument guildId: Long,
        @Argument name: String,
        @Argument type: String?,
        @Argument parentId: Long?,
        ctx: GraphQLContext,
    ): Channel {
        val me = ctx.requirePrincipal().userId
        service.require(guildId, me, Permission.MANAGE_CHANNELS)

        val channelType = when (type) {
            null, "GUILD_TEXT" -> ChannelType.GUILD_TEXT
            "GUILD_CATEGORY" -> ChannelType.GUILD_CATEGORY
            "GUILD_VOICE" -> ChannelType.GUILD_VOICE
            else -> throw InvalidInput("Channels in a server are text, voice or a category.")
        }

        val id = snowflake.next()
        val position = channels.channelsInGuild(guildId).size
        channels.insertGuildChannel(id, guildId, channelType, name.trim(), parentId, position)
        audit.record(me, AuditAction.CHANNEL_CREATE, targetId = id)

        return channels.findById(id) ?: error("Channel $id vanished after insert")
    }

    @MutationMapping
    fun createRole(
        @Argument guildId: Long,
        @Argument name: String,
        @Argument color: Int?,
        ctx: GraphQLContext,
    ): Role = service.createRole(guildId, ctx.requirePrincipal().userId, name, color)

    @MutationMapping
    fun updateRole(
        @Argument roleId: Long,
        @Argument name: String?,
        @Argument color: Int?,
        @Argument permissions: String?,
        @Argument hoist: Boolean?,
        @Argument mentionable: Boolean?,
        ctx: GraphQLContext,
    ): Role = service.updateRole(
        roleId = roleId,
        actorId = ctx.requirePrincipal().userId,
        name = name,
        color = color,
        permissions = permissions?.let(Permissions::parse),
        hoist = hoist,
        mentionable = mentionable,
    )

    @MutationMapping
    fun deleteRole(@Argument roleId: Long, ctx: GraphQLContext): Boolean =
        service.deleteRole(roleId, ctx.requirePrincipal().userId)

    @MutationMapping
    fun assignRole(
        @Argument guildId: Long,
        @Argument userId: Long,
        @Argument roleId: Long,
        ctx: GraphQLContext,
    ): Boolean = service.assignRole(guildId, userId, roleId, ctx.requirePrincipal().userId)

    @MutationMapping
    fun unassignRole(
        @Argument guildId: Long,
        @Argument userId: Long,
        @Argument roleId: Long,
        ctx: GraphQLContext,
    ): Boolean = service.unassignRole(guildId, userId, roleId, ctx.requirePrincipal().userId)

    @MutationMapping
    fun setNickname(
        @Argument guildId: Long,
        @Argument userId: Long?,
        @Argument nickname: String?,
        ctx: GraphQLContext,
    ): Boolean {
        val me = ctx.requirePrincipal().userId
        return service.setNickname(guildId, userId ?: me, me, nickname)
    }

    @MutationMapping
    fun kickMember(
        @Argument guildId: Long,
        @Argument userId: Long,
        ctx: GraphQLContext,
    ): Boolean = service.kick(guildId, userId, ctx.requirePrincipal().userId)

    @MutationMapping
    fun timeoutMember(
        @Argument guildId: Long,
        @Argument userId: Long,
        @Argument until: Instant?,
        ctx: GraphQLContext,
    ): Boolean {
        service.timeout(guildId, userId, ctx.requirePrincipal().userId, until)
        return true
    }

    @MutationMapping
    fun setChannelOverwrite(
        @Argument channelId: Long,
        @Argument targetId: Long,
        @Argument targetType: Int,
        @Argument allow: String,
        @Argument deny: String,
        ctx: GraphQLContext,
    ): Boolean {
        val me = ctx.requirePrincipal().userId
        val channel = channels.findById(channelId) ?: throw NotFound("Channel")
        val guildId = channel.guildId ?: throw InvalidInput("That channel isn't in a server.")

        val actor = service.require(guildId, me, Permission.MANAGE_ROLES)

        // Same escalation guard as editing a role: an overwrite that grants a permission you
        // don't hold is just a slower way of promoting yourself.
        if (!actor.isOwner) {
            val own = engine.basePermissions(actor)
            val escalation = Permissions.parse(allow) without own
            if (!escalation.isEmpty) throw Forbidden("permissions you don't have yourself")
        }

        guilds.upsertOverwrite(
            channelId, targetId, targetType.toShort(),
            Permissions.parse(allow), Permissions.parse(deny),
        )
        audit.record(me, AuditAction.OVERWRITE_UPDATE, targetId = channelId)
        return true
    }

    @MutationMapping
    fun clearChannelOverwrite(
        @Argument channelId: Long,
        @Argument targetId: Long,
        ctx: GraphQLContext,
    ): Boolean {
        val me = ctx.requirePrincipal().userId
        val channel = channels.findById(channelId) ?: throw NotFound("Channel")
        val guildId = channel.guildId ?: throw InvalidInput("That channel isn't in a server.")
        service.require(guildId, me, Permission.MANAGE_ROLES)
        return guilds.deleteOverwrite(channelId, targetId)
    }

    @MutationMapping
    fun createInvite(
        @Argument guildId: Long,
        @Argument channelId: Long?,
        @Argument maxUses: Int?,
        @Argument expiresAt: Instant?,
        ctx: GraphQLContext,
    ): Invite = service.createInvite(
        guildId, ctx.requirePrincipal().userId, channelId, maxUses, expiresAt,
    )

    @MutationMapping
    fun redeemInvite(@Argument code: String, ctx: GraphQLContext): GuildView {
        val me = ctx.requirePrincipal().userId
        return GuildView(service.redeemInvite(code.trim(), me), me)
    }

    @MutationMapping
    fun deleteInvite(@Argument code: String, ctx: GraphQLContext): Boolean {
        val me = ctx.requirePrincipal().userId
        val invite = guilds.findInvite(code) ?: throw NotFound("Invite")
        service.require(invite.guildId, me, Permission.MANAGE_INVITES)
        return guilds.deleteInvite(code)
    }

    @MutationMapping
    fun saveFolders(
        @Argument folders: List<GuildFolderInput>,
        @Argument loose: List<Long>,
        ctx: GraphQLContext,
    ): FolderLayout {
        val me = ctx.requirePrincipal().userId
        val layout = FolderLayout(
            folders = folders.map { GuildFolder(it.id, it.name, it.color, it.guildIds) },
            loose = loose.map(Long::toString),
        )
        this.folders.save(me, layout)
        return layout
    }

    // -- Field resolvers -----------------------------------------------------

    @SchemaMapping(typeName = "Guild", field = "id")
    fun guildId(v: GuildView) = v.guild.id

    @SchemaMapping(typeName = "Guild", field = "name")
    fun guildName(v: GuildView) = v.guild.name

    @SchemaMapping(typeName = "Guild", field = "iconKey")
    fun guildIcon(v: GuildView) = v.guild.iconKey

    @SchemaMapping(typeName = "Guild", field = "bannerKey")
    fun guildBanner(v: GuildView) = v.guild.bannerKey

    @SchemaMapping(typeName = "Guild", field = "description")
    fun guildDescription(v: GuildView) = v.guild.description

    @SchemaMapping(typeName = "Guild", field = "ownerId")
    fun guildOwner(v: GuildView) = v.guild.ownerId

    @SchemaMapping(typeName = "Guild", field = "requires2faForModeration")
    fun guild2fa(v: GuildView) = v.guild.requires2faForModeration

    @SchemaMapping(typeName = "Guild", field = "createdAt")
    fun guildCreated(v: GuildView) = v.guild.createdAt

    @SchemaMapping(typeName = "Guild", field = "channels")
    fun guildChannels(v: GuildView): List<Channel> = channels.channelsInGuild(v.guild.id)

    @SchemaMapping(typeName = "Guild", field = "roles")
    fun guildRoles(v: GuildView): List<Role> = guilds.rolesOf(v.guild.id)

    @SchemaMapping(typeName = "Guild", field = "me")
    fun guildMe(v: GuildView): GuildMember? = guilds.findMember(v.guild.id, v.viewerId)

    @SchemaMapping(typeName = "Guild", field = "myPermissions")
    fun guildMyPermissions(v: GuildView): String =
        engine.basePermissions(service.contextOf(v.guild.id, v.viewerId)).toString()

    @SchemaMapping(typeName = "Role", field = "permissions")
    fun rolePermissions(role: Role): String = role.permissions.toString()

    /** @everyone is identified by its id matching the guild's, not by its name. */
    @SchemaMapping(typeName = "Role", field = "isDefault")
    fun roleIsDefault(role: Role): Boolean = role.id == role.guildId

    @SchemaMapping(typeName = "GuildMember", field = "user")
    fun memberUser(member: GuildMember): User? = users.findById(member.userId)

    /**
     * What to actually render for this person in this server.
     *
     * Nickname wins, then display name, then username. The fallback chain lives here so no
     * client has to reimplement it and get a different answer.
     */
    @SchemaMapping(typeName = "GuildMember", field = "displayName")
    fun memberDisplayName(member: GuildMember): String =
        member.nickname
            ?: users.findById(member.userId)?.let { it.displayName ?: it.username }
            ?: "Unknown"

    @SchemaMapping(typeName = "GuildMember", field = "roles")
    fun memberRoles(member: GuildMember): List<Role> =
        guilds.rolesForMember(member.guildId, member.userId)

    @SchemaMapping(typeName = "FolderLayout", field = "loose")
    fun folderLoose(layout: FolderLayout): List<String> = layout.loose

    @SchemaMapping(typeName = "GuildFolder", field = "guildIds")
    fun folderGuildIds(folder: GuildFolder): List<String> = folder.guildIds
}
