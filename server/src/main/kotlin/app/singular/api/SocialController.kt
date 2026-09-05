package app.singular.api

import app.singular.core.InvalidInput
import app.singular.domain.PresenceStatus
import app.singular.domain.User
import app.singular.presence.Presence
import app.singular.presence.PresenceService
import app.singular.social.SocialRepository
import app.singular.social.UserSettings
import app.singular.security.principalOrNull
import app.singular.security.requirePrincipal
import app.singular.user.UserRepository
import graphql.GraphQLContext
import org.springframework.graphql.data.method.annotation.Argument
import org.springframework.graphql.data.method.annotation.BatchMapping
import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.graphql.data.method.annotation.QueryMapping
import org.springframework.graphql.data.method.annotation.SchemaMapping
import org.springframework.graphql.data.method.annotation.SubscriptionMapping
import org.springframework.stereotype.Controller
import reactor.core.publisher.Flux
import java.time.Instant

data class SettingsInput(
    val chatLayout: String? = null,
    val themePrimary: Int? = null,
    val themeSecondary: Int? = null,
    val themeDark: Boolean? = null,
    val themePreset: ThemePreset? = null,
)

/**
 * The appearance presets the client ships.
 *
 * Validated here rather than trusted: the value is persisted and replayed to every device the
 * user signs in on, so a nonsense string would otherwise be a theme that only exists on one
 * client and is unparseable on the others. The enum is also the contract — add a preset to the
 * client, add it here, and both sides stay in step.
 *
 * The string forms are stored, never the ordinals: an ordinal silently reassigns everyone's
 * theme the moment someone inserts a preset in the middle of the list.
 */
enum class ThemePreset { EMBER, COCOA, SAGE, DUSK, SLATE }

@Controller
class SocialController(
    private val social: SocialRepository,
    private val presence: PresenceService,
    private val users: UserRepository,
    private val userService: app.singular.user.UserService,
    private val attachments: app.singular.media.AttachmentRepository,
    private val media: app.singular.media.MediaService,
) {

    // -- Presence ------------------------------------------------------------

    @MutationMapping
    fun setStatus(@Argument status: String, ctx: GraphQLContext): Presence {
        val principal = ctx.requirePrincipal()
        val parsed = runCatching { PresenceStatus.valueOf(status) }.getOrNull()
            ?: throw InvalidInput("Unknown status: $status")
        // OFFLINE is derived from having no connection, never chosen. Accepting it would let a
        // client claim to be offline while still receiving everything, which is what INVISIBLE
        // is actually for.
        if (parsed == PresenceStatus.OFFLINE) {
            throw InvalidInput("Use INVISIBLE to appear offline while staying connected.")
        }
        presence.setStatus(principal.userId, parsed)
        return presence.presenceOf(principal.userId, principal.userId)
    }

    @MutationMapping
    fun setCustomStatus(
        @Argument text: String?,
        @Argument emoji: String?,
        @Argument expiresAt: Instant?,
        ctx: GraphQLContext,
    ): Presence {
        val principal = ctx.requirePrincipal()
        presence.setCustomStatus(principal.userId, text?.trim()?.ifEmpty { null }, emoji, expiresAt)
        return presence.presenceOf(principal.userId, principal.userId)
    }

    @MutationMapping
    fun heartbeat(ctx: GraphQLContext): Boolean {
        presence.heartbeat(ctx.requirePrincipal().userId)
        return true
    }

    @SubscriptionMapping
    fun presenceChanged(ctx: GraphQLContext): Flux<Presence> {
        ctx.requirePrincipal()
        return presence.subscribe()
    }

    // -- Blocking ------------------------------------------------------------

    @MutationMapping
    fun blockUser(@Argument userId: Long, ctx: GraphQLContext): Boolean {
        val principal = ctx.requirePrincipal()
        if (userId == principal.userId) throw InvalidInput("You can't block yourself.")
        users.findById(userId) ?: throw InvalidInput("No such user.")
        social.block(principal.userId, userId)
        return true
    }

    @MutationMapping
    fun unblockUser(@Argument userId: Long, ctx: GraphQLContext): Boolean {
        social.unblock(ctx.requirePrincipal().userId, userId)
        return true
    }

    @QueryMapping
    fun blockedUsers(ctx: GraphQLContext): List<User> {
        val ids = social.blockedBy(ctx.requirePrincipal().userId)
        return users.findAllById(ids).values.sortedBy { it.username }
    }

    // -- Muting --------------------------------------------------------------

    @MutationMapping
    fun muteUser(@Argument userId: Long, @Argument until: Instant?, ctx: GraphQLContext): Boolean {
        val principal = ctx.requirePrincipal()
        if (userId == principal.userId) throw InvalidInput("You can't mute yourself.")
        social.muteUser(principal.userId, userId, until)
        return true
    }

    @MutationMapping
    fun unmuteUser(@Argument userId: Long, ctx: GraphQLContext): Boolean =
        social.unmuteUser(ctx.requirePrincipal().userId, userId)

    @MutationMapping
    fun muteChannel(
        @Argument channelId: Long,
        @Argument until: Instant?,
        ctx: GraphQLContext,
    ): Boolean = social.muteChannel(channelId, ctx.requirePrincipal().userId, until)

    @MutationMapping
    fun unmuteChannel(@Argument channelId: Long, ctx: GraphQLContext): Boolean =
        social.muteChannel(channelId, ctx.requirePrincipal().userId, until = null)

    // -- Settings ------------------------------------------------------------

    @QueryMapping
    fun settings(ctx: GraphQLContext): UserSettings =
        social.settings(ctx.requirePrincipal().userId)

    @MutationMapping
    fun updateSettings(@Argument input: SettingsInput, ctx: GraphQLContext): UserSettings {
        val principal = ctx.requirePrincipal()
        val current = social.settings(principal.userId)

        // Every field is nullable and null means "leave it alone" — so a client changing only
        // the layout doesn't have to send the theme back and risk clobbering it.
        val merged = UserSettings(
            chatLayout = input.chatLayout?.let(::layoutCode) ?: current.chatLayout,
            themePrimary = input.themePrimary ?: current.themePrimary,
            themeSecondary = input.themeSecondary ?: current.themeSecondary,
            themeDark = input.themeDark ?: current.themeDark,
            // Enum binding already rejected unknown names, so this is the "explicitly cleared"
            // case only: a client sending null means leave it, which is why clearing a preset
            // back to the default is done by sending DEFAULT rather than by sending nothing.
            themePreset = input.themePreset?.name ?: current.themePreset,
        )
        social.saveSettings(principal.userId, merged)
        return merged
    }

    private fun layoutCode(name: String) = when (name) {
        "BUBBLES" -> 0
        "COMPACT" -> 1
        else -> throw InvalidInput("Unknown layout: $name")
    }

    @MutationMapping
    fun changeUsername(@Argument username: String, ctx: GraphQLContext): User =
        userService.changeUsername(ctx.requirePrincipal().userId, username)

    /**
     * A signed URL for the avatar.
     *
     * `avatar_key` holds an attachment id rather than a raw object key, so a profile picture
     * goes through the same pipeline as anything else off a user's disk — EXIF stripped,
     * thumbnailed, size-checked. The indirection is what makes that reuse possible.
     *
     * The **thumbnail** is preferred here, not the original: an avatar is drawn at 32-64px in
     * a list of dozens, and serving the full-size upload for each would move megabytes to
     * render a few hundred pixels.
     *
     * Null for a missing or still-uploading picture, rather than a broken link.
     */
    @SchemaMapping(typeName = "User", field = "avatarUrl")
    fun avatarUrl(user: User): String? {
        val attachmentId = user.avatarKey?.toLongOrNull() ?: return null
        val attachment = attachments.find(attachmentId) ?: return null
        if (attachment.status != app.singular.media.AttachmentStatus.READY) return null
        return media.downloadUrl(attachment.thumbnailKey ?: attachment.objectKey)
    }

    @MutationMapping
    fun updateProfile(
        @Argument displayName: String?,
        @Argument avatarKey: String?,
        @Argument bannerKey: String?,
        @Argument borderKey: String?,
        @Argument bio: String?,
        @Argument pronouns: String?,
        @Argument accentColor: Int?,
        ctx: GraphQLContext,
    ): User {
        val me = ctx.requirePrincipal().userId
        if (bio != null && bio.length > 512) throw InvalidInput("Bios are up to 512 characters.")
        users.updateProfile(me, displayName, avatarKey, bannerKey, borderKey, bio, pronouns, accentColor)
        return users.findById(me) ?: throw InvalidInput("Account not found.")
    }

    @SchemaMapping(typeName = "UserSettings", field = "chatLayout")
    fun settingsLayout(s: UserSettings): String = if (s.chatLayout == 1) "COMPACT" else "BUBBLES"

    /**
     * The stored string, narrowed to the enum.
     *
     * Returns null when absent — meaning "use the app default", which the client resolves — and
     * when the stored value names a preset this build no longer ships. A retired preset should
     * send a user to the default theme, not raise an error they can't act on.
     */
    @SchemaMapping(typeName = "UserSettings", field = "themePreset")
    fun settingsPreset(s: UserSettings): ThemePreset? =
        s.themePreset?.let { runCatching { ThemePreset.valueOf(it) }.getOrNull() }

    // -- User field resolvers ------------------------------------------------

    /**
     * Batched. Presence is on USER_FIELDS, so it resolves for every user in every response —
     * a per-user resolver would fire one query per member of every list on screen.
     */
    @BatchMapping(typeName = "User", field = "presence")
    fun userPresence(users: List<User>, ctx: GraphQLContext): Map<User, Presence> {
        val viewer = ctx.principalOrNull()?.userId
        val loaded = presence.presenceFor(users.map { it.id }.toSet(), viewer)
        return users.associateWith {
            loaded[it.id] ?: Presence(it.id, PresenceStatus.OFFLINE)
        }
    }

    @BatchMapping(typeName = "User", field = "blockedByViewer")
    fun userBlocked(users: List<User>, ctx: GraphQLContext): Map<User, Boolean> {
        val viewer = ctx.principalOrNull() ?: return users.associateWith { false }
        val blocked = social.blockedBy(viewer.userId)
        return users.associateWith { it.id in blocked }
    }

    @SchemaMapping(typeName = "Presence", field = "status")
    fun presenceStatus(p: Presence): String = p.status.name
}
