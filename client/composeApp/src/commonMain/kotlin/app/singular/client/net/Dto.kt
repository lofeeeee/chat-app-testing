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
) {
    /** 1:1 DMs have no name — they're titled after whoever isn't you. */
    fun title(selfId: String?): String =
        name ?: members.firstOrNull { it.id != selfId }?.label ?: "Direct message"
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

@Serializable data class CreateUploadData(@SerialName("createUpload") val slot: UploadSlotDto)
@Serializable data class FinalizeUploadData(@SerialName("finalizeUpload") val attachment: AttachmentDto)
@Serializable data class StoryFeedData(@SerialName("storyFeed") val stories: List<StoryDto>)
@Serializable data class CreateStoryData(@SerialName("createStory") val story: StoryDto)
@Serializable data class SendLocationData(@SerialName("sendLocation") val message: MessageDto)
