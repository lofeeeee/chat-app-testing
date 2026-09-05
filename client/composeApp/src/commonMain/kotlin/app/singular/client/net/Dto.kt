package app.singular.client.net

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Wire models.
 *
 * Every id is a String, deliberately. The server mints 64-bit snowflakes and serialises them
 * quoted; parsing them into a numeric type on any platform with a 53-bit safe integer range
 * (which includes the Wasm/JS target) would silently corrupt them. They are opaque handles
 * here — we sort by them as strings only where lengths match, and otherwise let the server
 * decide order.
 */

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    val discriminator: Int,
    val handle: String,
    val displayName: String? = null,
    val avatarKey: String? = null,
    /** Presigned and short-lived. Cache against [avatarKey], never against this. */
    val avatarUrl: String? = null,
    val bannerKey: String? = null,
    val borderKey: String? = null,
    val bio: String? = null,
    val pronouns: String? = null,
    val accentColor: Int? = null,
    val presence: PresenceDto? = null,
    val blockedByViewer: Boolean = false,
) {
    val label: String get() = displayName ?: username
    val status: String get() = presence?.status ?: "OFFLINE"
}

@Serializable
data class PresenceDto(
    val userId: String,
    val status: String,
    val customText: String? = null,
    val customEmoji: String? = null,
)

@Serializable
data class UserSettingsDto(
    val chatLayout: String = "BUBBLES",
    /**
     * The preset name, or null for the app default.
     *
     * A string and not a sealed type: a future server may ship a preset this build has never
     * heard of, and the right response is then "fall back to the default", not "fail to
     * deserialise the user's entire settings payload". Resolution happens in [app.singular.client
     * .ui.Presets.byId], which is where the fallback lives.
     */
    val themePreset: String? = null,
    /** Legacy raw accents, honoured only when [themePreset] is null. */
    val themePrimary: Int? = null,
    val themeSecondary: Int? = null,
    val themeDark: Boolean? = null,
)

@Serializable
data class ChannelDto(
    val id: String,
    val type: String,
    val name: String? = null,
    val members: List<UserDto> = emptyList(),
    val lastMessageId: String? = null,
    /** The category this channel sits under, or null for an uncategorised one. */
    val parentId: String? = null,
    val lastMessage: LastMessageDto? = null,
) {
    /** 1:1 DMs have no name — they're titled after whoever isn't you. */
    fun title(selfId: String?): String =
        name ?: members.firstOrNull { it.id != selfId }?.label ?: "Direct message"

    val isCategory: Boolean get() = type == "GUILD_CATEGORY"
}

/**
 * Just enough of a message to draw a one-line sidebar preview.
 *
 * Deliberately not [MessageDto]: the channel list would otherwise pull every attachment's
 * presigned URL and every author's full profile for a line of grey text nobody clicks. The
 * server signs upload URLs on read, so asking for attachments here would be doing real work
 * per conversation on every sidebar load.
 */
@Serializable
data class LastMessageDto(
    val id: String,
    val content: String? = null,
    val createdAt: String,
    val author: UserDto,
    val attachments: List<AttachmentBriefDto> = emptyList(),
) {
    /**
     * The preview line, WhatsApp-style: "You: …" when it's yours, plain otherwise.
     *
     * An attachment with no caption gets a label rather than an empty row — "Photo" is what
     * you actually want to read there, and a blank line looks like a bug.
     *
     * [resolveMention] turns the wire form into something readable. Without it a message whose
     * body is a mention previews as `You: <@221239599735771136>`, which is not a preview of
     * anything — the sidebar was showing an id where the conversation shows a name. It
     * defaults to identity so a caller with no user data still gets the text, just unresolved.
     */
    fun preview(selfId: String?, resolveMention: (String) -> String = { it }): String {
        val readable = content?.let { MENTION_WIRE.replace(it) { m -> resolveMention(m.value) } }
        val body = readable?.replace('\n', ' ')?.trim().orEmpty().ifEmpty {
            when (attachments.firstOrNull()?.kind) {
                "IMAGE" -> "Photo"
                "VIDEO" -> "Video"
                "VOICE_NOTE" -> "Voice message"
                "AUDIO" -> "Audio"
                null -> "Shared a location"
                else -> "Attachment"
            }
        }
        return if (author.id == selfId) "You: $body" else body
    }

    private companion object {
        /** Mirrors the renderer's pattern — one place could drift, two definitely would. */
        val MENTION_WIRE = Regex("""<@&?\d{1,20}>|<#\d{1,20}>""")
    }
}

@Serializable
data class AttachmentBriefDto(val id: String, val kind: String)

/**
 * Orders two snowflake id strings by age.
 *
 * Ids stay strings the whole way through (see the note at the top of this file), so "newer
 * than" cannot be a numeric comparison. Snowflakes are time-sortable and unsigned, which makes
 * a longer decimal string always the larger number — hence length first, then lexicographic
 * within a length. Comparing them as plain strings would get this wrong the day ids gain a
 * digit, and would do so silently.
 */
fun isNewerSnowflake(candidate: String, than: String): Boolean = when {
    candidate.length != than.length -> candidate.length > than.length
    else -> candidate > than
}

@Serializable
data class MessageDto(
    val id: String,
    val channelId: String,
    val author: UserDto,
    val content: String? = null,
    val createdAt: String,
    val editedAt: String? = null,
    val authorBlocked: Boolean = false,
    val attachments: List<AttachmentDto> = emptyList(),
    val location: LocationDto? = null,
    val mentions: List<MentionDto> = emptyList(),
    val reactions: List<ReactionDto> = emptyList(),
)

@Serializable
data class MentionDto(
    val type: String,
    val targetId: String? = null,
)

@Serializable
data class ReactionDto(
    val emoji: String,
    val count: Int,
    val me: Boolean = false,
)

@Serializable
data class ReactionUpdateDto(
    val messageId: String,
    val channelId: String,
    val reactions: List<ReactionDto> = emptyList(),
)

@Serializable
data class AttachmentDto(
    val id: String,
    val filename: String,
    val contentType: String,
    val sizeBytes: String,
    val kind: String,
    val status: String,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Int? = null,
    val waveform: List<Int> = emptyList(),
    /** Presigned and short-lived. Never cache it — re-fetch the message instead. */
    val url: String? = null,
    val thumbnailUrl: String? = null,
) {
    val isImage: Boolean get() = kind == "IMAGE"
    val isVoice: Boolean get() = kind == "VOICE_NOTE"

    val readableSize: String get() {
        val bytes = sizeBytes.toLongOrNull() ?: return ""
        return when {
            bytes >= 1024L * 1024 -> (bytes / 1024 / 1024).toString() + " MB"
            bytes >= 1024 -> (bytes / 1024).toString() + " KB"
            else -> bytes.toString() + " B"
        }
    }
}

@Serializable
data class LocationDto(
    val latitude: Double,
    val longitude: Double,
    val label: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class UploadSlotDto(
    val attachment: AttachmentDto,
    val uploadUrl: String,
)

@Serializable
data class StoryDto(
    val id: String,
    val author: UserDto,
    val attachment: AttachmentDto? = null,
    val background: String? = null,
    val overlays: String = "[]",
    val createdAt: String,
    val expiresAt: String,
    val viewCount: Int = 0,
    val seen: Boolean = false,
)

@Serializable
data class MessagePageDto(
    val nodes: List<MessageDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

@Serializable
data class AuthPayloadDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Int,
    val user: UserDto,
)

// -- GraphQL envelope --------------------------------------------------------

/** Variables are a raw JsonObject — the alternative is a polymorphic Any serializer nobody wants. */
@Serializable
data class GraphQlRequest(
    val query: String,
    val variables: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class GraphQlError(
    val message: String,
    val extensions: Map<String, String>? = null,
) {
    val code: String? get() = extensions?.get("code")
}

@Serializable
data class GraphQlResponse<T>(
    val data: T? = null,
    val errors: List<GraphQlError>? = null,
)

// Named response wrappers — one per operation, so deserialization stays type-safe.
@Serializable data class RegisterData(val register: AuthPayloadDto)
@Serializable data class LoginData(val login: AuthPayloadDto)
@Serializable data class RefreshData(val refresh: AuthPayloadDto)
@Serializable data class MeData(val me: UserDto? = null)
@Serializable data class ChannelsData(val channels: List<ChannelDto>)
@Serializable data class MessagesData(val messages: MessagePageDto)
@Serializable data class SendMessageData(@SerialName("sendMessage") val message: MessageDto)
@Serializable data class OpenDmData(@SerialName("openDirectMessage") val channel: ChannelDto)
@Serializable data class UserByHandleData(val userByHandle: UserDto? = null)
@Serializable data class MessageCreatedData(@SerialName("messageCreated") val message: MessageDto)
@Serializable data class NotificationsData(@SerialName("notifications") val message: MessageDto)
@Serializable data class AddReactionData(@SerialName("addReaction") val message: MessageDto)
@Serializable data class RemoveReactionData(@SerialName("removeReaction") val message: MessageDto)
@Serializable data class ReactionUpdatedData(@SerialName("reactionUpdated") val update: ReactionUpdateDto)
@Serializable data class MentionInboxData(@SerialName("mentionInbox") val messages: List<MessageDto>)
@Serializable data class SetCustomStatusData(@SerialName("setCustomStatus") val presence: PresenceDto)

// -- Session management ------------------------------------------------------

@Serializable
data class DeviceSessionDto(
    val id: String,
    val deviceId: String,
    val platform: String? = null,
    val userAgent: String? = null,
    val ipAddress: String? = null,
    val origin: String,
    val firstSeenAt: String,
    val lastSeenAt: String,
    val current: Boolean,
) {
    val label: String get() = platform ?: userAgent ?: "Unknown device"
}

// -- QR sign-in --------------------------------------------------------------

@Serializable
data class LoginRequestDto(
    val id: String,
    val qrPayload: String,
    val status: String,
    val tokenExpiresAt: String,
    val expiresAt: String,
    val rotateAfterSeconds: Int,
)

@Serializable
data class NewLoginRequestDto(
    val request: LoginRequestDto,
    val pollSecret: String,
)

@Serializable
data class ScannedLoginRequestDto(
    val id: String,
    val ipAddress: String? = null,
    val platform: String? = null,
    val userAgent: String? = null,
    val requestedAt: String,
)

@Serializable
data class LoginRequestEventDto(
    val status: String,
    val approvedBy: UserDto? = null,
    val auth: AuthPayloadDto? = null,
)

@Serializable data class SessionsData(val sessions: List<DeviceSessionDto>)
@Serializable data class RevokeSessionData(@SerialName("revokeSession") val ok: Boolean)
@Serializable data class RevokeOthersData(@SerialName("revokeOtherSessions") val count: Int)
@Serializable data class CreateLoginData(@SerialName("createLoginRequest") val created: NewLoginRequestDto)
@Serializable data class RotateLoginData(@SerialName("rotateLoginToken") val request: LoginRequestDto)
@Serializable data class ClaimLoginData(@SerialName("claimLoginRequest") val scanned: ScannedLoginRequestDto)
@Serializable data class ApproveLoginData(@SerialName("approveLoginRequest") val ok: Boolean)
@Serializable data class DenyLoginData(@SerialName("denyLoginRequest") val ok: Boolean)
@Serializable data class LoginRequestUpdatedData(@SerialName("loginRequestUpdated") val event: LoginRequestEventDto)

// -- Typing indicators -------------------------------------------------------

@Serializable
data class TypingEventDto(
    val channelId: String,
    val user: UserDto,
    val at: String,
)

@Serializable data class TypingData(@SerialName("typing") val event: TypingEventDto)
@Serializable data class StartTypingData(@SerialName("startTyping") val ok: Boolean)

@Serializable data class SettingsData(val settings: UserSettingsDto)
@Serializable data class UpdateSettingsData(@SerialName("updateSettings") val settings: UserSettingsDto)
@Serializable data class SetStatusData(@SerialName("setStatus") val presence: PresenceDto)
@Serializable data class PresenceChangedData(@SerialName("presenceChanged") val presence: PresenceDto)
@Serializable data class BlockData(@SerialName("blockUser") val ok: Boolean)
@Serializable data class UnblockData(@SerialName("unblockUser") val ok: Boolean)
@Serializable data class MuteChannelData(@SerialName("muteChannel") val ok: Boolean)
@Serializable data class UnmuteChannelData(@SerialName("unmuteChannel") val ok: Boolean)
@Serializable data class HeartbeatData(val heartbeat: Boolean)

@Serializable data class UpdateProfileData(@SerialName("updateProfile") val user: UserDto)
@Serializable data class ChangeUsernameData(@SerialName("changeUsername") val user: UserDto)

@Serializable data class CreateUploadData(@SerialName("createUpload") val slot: UploadSlotDto)
@Serializable data class FinalizeUploadData(@SerialName("finalizeUpload") val attachment: AttachmentDto)
@Serializable data class StoryFeedData(@SerialName("storyFeed") val stories: List<StoryDto>)
@Serializable data class CreateStoryData(@SerialName("createStory") val story: StoryDto)
@Serializable data class SendLocationData(@SerialName("sendLocation") val message: MessageDto)

// -- Servers (guilds) --------------------------------------------------------

@Serializable
data class GuildDto(
    val id: String,
    val name: String,
    val iconKey: String? = null,
    /** Presigned and short-lived. Cache against [iconKey], never against this. */
    val iconUrl: String? = null,
    val description: String? = null,
    val ownerId: String,
    val channels: List<ChannelDto> = emptyList(),
    val roles: List<RoleDto> = emptyList(),
    val me: GuildMemberDto? = null,
    /** 128-bit bitfield as a decimal string. Never parse it into a number. */
    val myPermissions: String = "0",
) {
    /** Two letters is what fits a 48dp rail tile; one reads as an accident. */
    val initials: String get() = name.trim()
        .split(Regex("\\s+"))
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercase() }
        .joinToString("")
        .ifEmpty { "?" }
}

@Serializable
data class RoleDto(
    val id: String,
    val name: String,
    val color: Int? = null,
    val position: Int = 0,
    val permissions: String = "0",
    val hoist: Boolean = false,
    val mentionable: Boolean = false,
    val isDefault: Boolean = false,
)

@Serializable
data class GuildMemberDto(
    val guildId: String,
    val user: UserDto,
    val nickname: String? = null,
    /** Nickname, else display name, else username. Resolved server-side so clients agree. */
    val displayName: String,
    val roles: List<RoleDto> = emptyList(),
)

@Serializable
data class InviteDto(
    val code: String,
    val guildId: String,
    val uses: Int = 0,
    val maxUses: Int? = null,
)

@Serializable data class GuildsData(val guilds: List<GuildDto>)
@Serializable data class GuildData(val guild: GuildDto? = null)
@Serializable data class CreateGuildData(@SerialName("createGuild") val guild: GuildDto)
@Serializable data class UpdateGuildData(@SerialName("updateGuild") val guild: GuildDto)
@Serializable data class InvitesData(val invites: List<InviteDto>)
@Serializable data class RedeemInviteData(@SerialName("redeemInvite") val guild: GuildDto)
@Serializable data class CreateInviteData(@SerialName("createInvite") val invite: InviteDto)
@Serializable data class GuildMembersData(val guildMembers: List<GuildMemberDto>)
@Serializable data class CreateGuildChannelData(@SerialName("createGuildChannel") val channel: ChannelDto)

@Serializable data class CreateRoleData(@SerialName("createRole") val role: RoleDto)
@Serializable data class UpdateRoleData(@SerialName("updateRole") val role: RoleDto)
@Serializable data class DeleteRoleData(@SerialName("deleteRole") val ok: Boolean)
@Serializable data class AssignRoleData(@SerialName("assignRole") val ok: Boolean)
@Serializable data class UnassignRoleData(@SerialName("unassignRole") val ok: Boolean)
@Serializable data class KickMemberData(@SerialName("kickMember") val ok: Boolean)
@Serializable data class DeleteInviteData(@SerialName("deleteInvite") val ok: Boolean)
