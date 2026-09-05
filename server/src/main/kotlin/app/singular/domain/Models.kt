package app.singular.domain

import java.time.Instant
import java.util.UUID

data class User(
    val id: Long,
    val username: String,
    val discriminator: Short,
    val displayName: String?,
    val avatarKey: String?,
    val bannerKey: String?,
    val bio: String?,
    val accentColor: Int?,
    val borderKey: String?,
    val pronouns: String?,
    val createdAt: Instant,
) {
    /** Rendered handle, e.g. `alex#0971`. Always four digits — the leading zeros are load-bearing. */
    val handle: String get() = "$username#%04d".format(discriminator)
}

/** Only ever loaded by the auth path; keeps the password hash off the general read model. */
data class UserCredentials(
    val id: Long,
    val passwordHash: String,
)

/**
 * INVISIBLE is stored but never reported as itself — other people see OFFLINE. It is listed
 * here rather than in a separate type because the user's own client does need to see it.
 */
enum class PresenceStatus(val code: Short) {
    ONLINE(0), AWAY(1), DND(2), INVISIBLE(3), OFFLINE(4);

    companion object {
        fun ofCode(code: Short) = entries.firstOrNull { it.code == code } ?: ONLINE
    }
}

enum class ChannelType(val code: Short) {
    DM(0), GROUP_DM(1), GUILD_TEXT(2), GUILD_CATEGORY(3), GUILD_VOICE(4);

    companion object {
        fun of(code: Short) = entries.firstOrNull { it.code == code }
            ?: error("Unknown channel type code: $code")
    }
}

data class Channel(
    val id: Long,
    val guildId: Long?,
    val type: ChannelType,
    val name: String?,
    val iconKey: String?,
    val ownerId: Long?,
    val lastMessageId: Long?,
    /** The GUILD_CATEGORY channel this one sits under, or null. Always null for DMs. */
    val parentId: Long?,
    val createdAt: Instant,
)

data class Message(
    val id: Long,
    val channelId: Long,
    val authorId: Long,
    val content: String?,
    val replyToId: Long?,
    val createdAt: Instant,
    val editedAt: Instant?,
    // Columns rather than an attachment: a shared location has no file, and modelling it as
    // one would make every message read join a table for what amounts to two floats.
    val locationLat: Double? = null,
    val locationLon: Double? = null,
    val locationLabel: String? = null,
    val locationExpiresAt: Instant? = null,
)

data class AuthSession(
    val id: Long,
    val userId: Long,
    val familyId: Long,
    val deviceId: UUID,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val supersededBy: Long?,
)

/** Codes are persisted in `login_requests.status` — append only, never renumber. */
enum class LoginRequestStatus(val code: Short) {
    PENDING(0), SCANNED(1), APPROVED(2), DENIED(3), EXPIRED(4), CONSUMED(5);

    companion object {
        fun of(code: Short) = entries.firstOrNull { it.code == code }
            ?: error("Unknown login request status: $code")
    }
}

data class LoginRequest(
    val id: Long,
    val status: LoginRequestStatus,
    val claimedBy: Long?,
    val requestIp: String?,
    val requestUserAgent: String?,
    val requestPlatform: String?,
    val requestDeviceId: UUID?,
    val createdAt: Instant,
    val tokenExpiresAt: Instant,
    val expiresAt: Instant,
)

/** How a session was created. Persisted in `auth_sessions.created_via`. */
enum class SessionOrigin(val code: Short) {
    PASSWORD(0), QR_CODE(1), REFRESH(2);

    companion object {
        fun of(code: Short) = entries.firstOrNull { it.code == code } ?: PASSWORD
    }
}

/**
 * One entry in the user's "where you're signed in" list.
 *
 * Keyed by `family_id`, not session id. A refresh rotation mints a new `auth_sessions` row
 * every 15 minutes, so listing raw rows would show the same laptop dozens of times a day.
 * The family is the thing a person recognises as "a device".
 */
data class DeviceSession(
    val familyId: Long,
    val liveSessionId: Long,
    val deviceId: UUID,
    val userAgent: String?,
    val ipAddress: String?,
    val platform: String?,
    val origin: SessionOrigin,
    val firstSeenAt: Instant,
    val lastSeenAt: Instant,
    val expiresAt: Instant,
)

/** Codes are persisted in `audit_events.action` — append only, never renumber. */
enum class AuditAction(val code: Short) {
    REGISTER(1),
    LOGIN(2),
    LOGIN_FAILED(3),
    LOGOUT(4),
    TOKEN_REFRESH(5),
    TOKEN_REUSE_DETECTED(6),
    PASSWORD_CHANGE(7),
    USERNAME_CHANGE(8),
    AVATAR_CHANGE(9),
    PROFILE_CHANGE(10),
    CHANNEL_CREATE(20),
    CHANNEL_DELETE(21),
    MESSAGE_DELETE(22),
    QR_LOGIN_REQUESTED(30),
    QR_LOGIN_SCANNED(31),
    QR_LOGIN_APPROVED(32),
    QR_LOGIN_DENIED(33),
    SESSION_REVOKED(34),

    GUILD_CREATE(40),
    GUILD_UPDATE(41),
    GUILD_DELETE(42),
    ROLE_CREATE(43),
    ROLE_UPDATE(44),
    ROLE_DELETE(45),
    ROLE_GRANT(46),
    ROLE_REVOKE(47),
    MEMBER_JOIN(48),
    MEMBER_KICK(49),
    MEMBER_TIMEOUT(50),
    NICKNAME_CHANGE(51),
    OVERWRITE_UPDATE(52),
}

/** What the client tells us about itself at sign-in. Never trusted for identity, only recorded. */
data class ClientContext(
    val ip: String?,
    val userAgent: String?,
    val deviceId: UUID,
    /** Self-reported, e.g. "Windows desktop". Shown on the QR approval screen. */
    val platform: String? = null,
)
