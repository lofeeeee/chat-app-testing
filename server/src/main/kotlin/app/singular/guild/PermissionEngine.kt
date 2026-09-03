package app.singular.guild

import org.springframework.stereotype.Component

/** A role as the engine needs it. Ordering is by [position], ties broken by id. */
data class Role(
    val id: Long,
    val guildId: Long,
    val name: String,
    val color: Int?,
    val iconKey: String?,
    val position: Int,
    val permissions: Permissions,
    val hoist: Boolean,
    val mentionable: Boolean,
    val managedBy: Long?,
) : Comparable<Role> {
    /** Higher position outranks; equal positions break by id, so ordering is total. */
    override fun compareTo(other: Role): Int =
        compareValuesBy(this, other, { it.position }, { it.id })
}

/** One channel overwrite. [targetType] 0 = role, 1 = member. */
data class Overwrite(
    val targetId: Long,
    val targetType: Short,
    val allow: Permissions,
    val deny: Permissions,
) {
    val isRole: Boolean get() = targetType == 0.toShort()
    val isMember: Boolean get() = targetType == 1.toShort()
}

/** Everything needed to resolve one person's permissions, loaded once. */
data class MemberContext(
    val userId: Long,
    val guildId: Long,
    val guildOwnerId: Long,
    /** Every role the member holds, including @everyone. */
    val roles: List<Role>,
) {
    val isOwner: Boolean get() = userId == guildOwnerId

    /** Highest role held. Drives every "can I act on this person" check. */
    val highestRole: Role? get() = roles.maxOrNull()
}

/**
 * Resolves effective permissions.
 *
 * The order below is not stylistic — it *is* the security model, and getting a step out of
 * place produces holes that look like ordinary bugs. It mirrors Discord's published algorithm
 * because that model is well designed and widely understood, so anyone who has administered a
 * Discord server already knows how this behaves.
 */
@Component
class PermissionEngine {

    /**
     * Server-wide permissions, before any channel is considered.
     *
     * 1. The owner bypasses everything, always.
     * 2. Otherwise: the union of every role the member holds, @everyone included.
     * 3. ADMINISTRATOR short-circuits to everything.
     */
    fun basePermissions(member: MemberContext): Permissions {
        if (member.isOwner) return Permissions.ALL

        val combined = member.roles.fold(Permissions.NONE) { acc, role -> acc or role.permissions }
        return if (combined has Permission.ADMINISTRATOR) Permissions.ALL else combined
    }

    /**
     * Permissions inside one channel.
     *
     * ```
     * 1. owner                      -> everything
     * 2. base = OR of all roles
     * 3. ADMINISTRATOR              -> everything, bypassing every overwrite below
     * 4. @everyone overwrite        -> deny, then allow
     * 5. role overwrites            -> UNIONED first, then applied as one
     * 6. member overwrite           -> deny, then allow. Beats everything else.
     * 7. no VIEW_CHANNEL            -> nothing
     * ```
     *
     * Step 5 is the one people get wrong. Role overwrites are accumulated into a single
     * allow-mask and a single deny-mask *before* either is applied. Applying them one role at
     * a time makes the outcome depend on the order roles happen to come back in, which
     * produces permission bugs nobody can reproduce — and means a grant on any role correctly
     * beats a deny on another, rather than whichever happened to be applied last.
     */
    fun permissionsIn(member: MemberContext, overwrites: List<Overwrite>): Permissions {
        if (member.isOwner) return Permissions.ALL

        var permissions = basePermissions(member)
        if (permissions has Permission.ADMINISTRATOR) return Permissions.ALL

        val byTarget = overwrites.associateBy { it.targetId }

        // 4. @everyone. Its role id equals the guild id, so no special case is needed.
        byTarget[member.guildId]?.let { everyone ->
            permissions = (permissions without everyone.deny) or everyone.allow
        }

        // 5. Role overwrites: gather, then apply once.
        var allow = Permissions.NONE
        var deny = Permissions.NONE
        member.roles
            .filter { it.id != member.guildId }        // @everyone already handled above
            .forEach { role ->
                byTarget[role.id]?.takeIf { it.isRole }?.let { ow ->
                    allow = allow or ow.allow
                    deny = deny or ow.deny
                }
            }
        permissions = (permissions without deny) or allow

        // 6. A member-specific overwrite is the final word.
        byTarget[member.userId]?.takeIf { it.isMember }?.let { own ->
            permissions = (permissions without own.deny) or own.allow
        }

        // 7. Can't see the channel -> can't do anything in it. Without this, someone denied
        //    VIEW_CHANNEL but granted SEND_MESSAGES could still post into a channel they are
        //    not supposed to know exists.
        if (!(permissions has Permission.VIEW_CHANNEL)) return Permissions.NONE

        return permissions
    }

    fun can(member: MemberContext, overwrites: List<Overwrite>, flag: Permission): Boolean =
        permissionsIn(member, overwrites).allows(flag)

    /**
     * Whether [actor] outranks [target] and may therefore act on them.
     *
     * The owner outranks everyone and is outranked by no one — including other administrators,
     * which is what stops an admin from removing the person who made them one.
     *
     * Everyone else is compared by highest role. Equal is *not* enough: two members holding the
     * same top role must not be able to kick each other, or any role with KICK_MEMBERS becomes
     * a circular firing squad.
     */
    fun outranks(actor: MemberContext, target: MemberContext): Boolean {
        if (actor.isOwner) return true
        if (target.isOwner) return false

        val actorTop = actor.highestRole ?: return false
        val targetTop = target.highestRole ?: return true
        return actorTop > targetTop
    }

    /**
     * Whether [actor] may create, edit or delete [role].
     *
     * Strictly below their own highest role, for the same reason as [outranks]: being able to
     * edit a role you hold is being able to grant yourself ADMINISTRATOR.
     */
    fun canManageRole(actor: MemberContext, role: Role): Boolean {
        if (actor.isOwner) return true
        if (!basePermissions(actor).allows(Permission.MANAGE_ROLES)) return false

        val actorTop = actor.highestRole ?: return false
        return role < actorTop
    }

    /** May [actor] give or take [role] on someone else? Same rule, stated separately for clarity. */
    fun canAssignRole(actor: MemberContext, target: MemberContext, role: Role): Boolean =
        canManageRole(actor, role) && outranks(actor, target)
}
