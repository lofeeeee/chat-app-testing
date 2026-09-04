package app.singular.client.net

/**
 * Hand-written operation documents.
 *
 * Fragments keep the field sets identical between a query and its matching subscription — if
 * `messages` and `messageCreated` ever disagree about which fields they return, the UI shows
 * one shape on load and a different one live, which is a genuinely confusing bug to chase.
 */
object Operations {

    private const val USER_FIELDS = """
        fragment UserFields on User {
            id username discriminator handle displayName avatarKey bannerKey
            bio pronouns accentColor borderKey
            blockedByViewer
            presence { userId status customText customEmoji }
        }
    """

    private const val ATTACHMENT_FIELDS = """
        fragment AttachmentFields on Attachment {
            id filename contentType sizeBytes kind status
            width height durationMs waveform url thumbnailUrl
        }
    """

    private const val MESSAGE_FIELDS = """
        fragment MessageFields on Message {
            id channelId content createdAt editedAt authorBlocked
            author { ...UserFields }
            attachments { ...AttachmentFields }
            location { latitude longitude label expiresAt }
        }
    """

    private const val CHANNEL_FIELDS = """
        fragment ChannelFields on Channel {
            id type name lastMessageId
            members { ...UserFields }
        }
    """

    val REGISTER = """
        $USER_FIELDS
        mutation Register(${'$'}input: RegisterInput!) {
            register(input: ${'$'}input) {
                accessToken refreshToken expiresInSeconds
                user { ...UserFields }
            }
        }
    """

    val LOGIN = """
        $USER_FIELDS
        mutation Login(${'$'}input: LoginInput!) {
            login(input: ${'$'}input) {
                accessToken refreshToken expiresInSeconds
                user { ...UserFields }
            }
        }
    """

    val REFRESH = """
        $USER_FIELDS
        mutation Refresh(${'$'}token: String!) {
            refresh(refreshToken: ${'$'}token) {
                accessToken refreshToken expiresInSeconds
                user { ...UserFields }
            }
        }
    """

    val CHANNELS = """
        $USER_FIELDS
        $CHANNEL_FIELDS
        query Channels { channels { ...ChannelFields } }
    """

    val MESSAGES = """
        $USER_FIELDS
        $ATTACHMENT_FIELDS
        $MESSAGE_FIELDS
        query Messages(${'$'}channelId: Snowflake!, ${'$'}before: Snowflake, ${'$'}limit: Int) {
            messages(channelId: ${'$'}channelId, before: ${'$'}before, limit: ${'$'}limit) {
                nodes { ...MessageFields }
                nextCursor
                hasMore
            }
        }
    """

    val SEND_MESSAGE = """
        $USER_FIELDS
        $ATTACHMENT_FIELDS
        $MESSAGE_FIELDS
        mutation Send(${'$'}input: SendMessageInput!) {
            sendMessage(input: ${'$'}input) { ...MessageFields }
        }
    """

    val OPEN_DM = """
        $USER_FIELDS
        $CHANNEL_FIELDS
        mutation OpenDm(${'$'}userId: Snowflake!) {
            openDirectMessage(userId: ${'$'}userId) { ...ChannelFields }
        }
    """

    val USER_BY_HANDLE = """
        $USER_FIELDS
        query ByHandle(${'$'}username: String!, ${'$'}discriminator: Int!) {
            userByHandle(username: ${'$'}username, discriminator: ${'$'}discriminator) {
                ...UserFields
            }
        }
    """

    val START_TYPING = """
        mutation StartTyping(${'$'}channelId: Snowflake!) {
            startTyping(channelId: ${'$'}channelId)
        }
    """

    val TYPING = """
        $USER_FIELDS
        subscription OnTyping(${'$'}channelId: Snowflake!) {
            typing(channelId: ${'$'}channelId) {
                channelId
                at
                user { ...UserFields }
            }
        }
    """

    val SETTINGS = """
        query Settings { settings { chatLayout themePreset themePrimary themeSecondary themeDark } }
    """

    val UPDATE_SETTINGS = """
        mutation UpdateSettings(${'$'}input: SettingsInput!) {
            updateSettings(input: ${'$'}input) {
                chatLayout themePreset themePrimary themeSecondary themeDark
            }
        }
    """

    val UPDATE_PROFILE = """
        $USER_FIELDS
        mutation UpdateProfile(
            ${'$'}displayName: String, ${'$'}bio: String, ${'$'}pronouns: String, ${'$'}accentColor: Int
        ) {
            updateProfile(
                displayName: ${'$'}displayName, bio: ${'$'}bio,
                pronouns: ${'$'}pronouns, accentColor: ${'$'}accentColor
            ) { ...UserFields }
        }
    """

    val SET_STATUS = """
        mutation SetStatus(${'$'}status: PresenceStatus!) {
            setStatus(status: ${'$'}status) { userId status customText customEmoji }
        }
    """

    val HEARTBEAT = """mutation { heartbeat }"""

    val PRESENCE_CHANGED = """
        subscription OnPresence {
            presenceChanged { userId status customText customEmoji }
        }
    """

    val BLOCK_USER = """
        mutation Block(${'$'}userId: Snowflake!) { blockUser(userId: ${'$'}userId) }
    """

    val UNBLOCK_USER = """
        mutation Unblock(${'$'}userId: Snowflake!) { unblockUser(userId: ${'$'}userId) }
    """

    val MUTE_CHANNEL = """
        mutation Mute(${'$'}channelId: Snowflake!) { muteChannel(channelId: ${'$'}channelId) }
    """

    val UNMUTE_CHANNEL = """
        mutation Unmute(${'$'}channelId: Snowflake!) { unmuteChannel(channelId: ${'$'}channelId) }
    """

    val CREATE_UPLOAD = """
        $ATTACHMENT_FIELDS
        mutation CreateUpload(
            ${'$'}filename: String!, ${'$'}contentType: String!,
            ${'$'}sizeBytes: String!, ${'$'}voiceNote: Boolean
        ) {
            createUpload(
                filename: ${'$'}filename, contentType: ${'$'}contentType,
                sizeBytes: ${'$'}sizeBytes, voiceNote: ${'$'}voiceNote
            ) {
                uploadUrl
                attachment { ...AttachmentFields }
            }
        }
    """

    val FINALIZE_UPLOAD = """
        $ATTACHMENT_FIELDS
        mutation FinalizeUpload(
            ${'$'}attachmentId: Snowflake!, ${'$'}durationMs: Int, ${'$'}waveform: [Int!]
        ) {
            finalizeUpload(
                attachmentId: ${'$'}attachmentId,
                durationMs: ${'$'}durationMs, waveform: ${'$'}waveform
            ) { ...AttachmentFields }
        }
    """

    val SEND_LOCATION = """
        $USER_FIELDS
        $ATTACHMENT_FIELDS
        $MESSAGE_FIELDS
        mutation SendLocation(
            ${'$'}channelId: Snowflake!, ${'$'}latitude: Float!,
            ${'$'}longitude: Float!, ${'$'}label: String
        ) {
            sendLocation(
                channelId: ${'$'}channelId, latitude: ${'$'}latitude,
                longitude: ${'$'}longitude, label: ${'$'}label
            ) { ...MessageFields }
        }
    """

    val STORY_FEED = """
        $USER_FIELDS
        $ATTACHMENT_FIELDS
        query StoryFeed {
            storyFeed {
                id createdAt expiresAt viewCount seen background overlays
                author { ...UserFields }
                attachment { ...AttachmentFields }
            }
        }
    """

    val CREATE_STORY = """
        $USER_FIELDS
        $ATTACHMENT_FIELDS
        mutation CreateStory(
            ${'$'}attachmentId: Snowflake, ${'$'}background: String, ${'$'}overlays: String
        ) {
            createStory(
                attachmentId: ${'$'}attachmentId,
                background: ${'$'}background, overlays: ${'$'}overlays
            ) {
                id createdAt expiresAt viewCount seen background overlays
                author { ...UserFields }
                attachment { ...AttachmentFields }
            }
        }
    """

    val MARK_STORY_SEEN = """
        mutation MarkSeen(${'$'}id: Snowflake!) { markStorySeen(id: ${'$'}id) }
    """

    val MESSAGE_CREATED = """
        $USER_FIELDS
        $ATTACHMENT_FIELDS
        $MESSAGE_FIELDS
        subscription OnMessage(${'$'}channelId: Snowflake!) {
            messageCreated(channelId: ${'$'}channelId) { ...MessageFields }
        }
    """
}

/** Session management and QR sign-in. Kept apart from [Operations] purely for readability. */
object AuthOperations {

    private const val USER_FIELDS = """
        fragment UserFields on User {
            id username discriminator handle displayName avatarKey bio accentColor
        }
    """

    private const val LOGIN_REQUEST_FIELDS = """
        fragment LoginRequestFields on LoginRequest {
            id qrPayload status tokenExpiresAt expiresAt rotateAfterSeconds
        }
    """

    val SESSIONS = """
        query Sessions {
            sessions {
                id deviceId platform userAgent ipAddress origin
                firstSeenAt lastSeenAt current
            }
        }
    """

    val REVOKE_SESSION = """
        mutation Revoke(${'$'}id: Snowflake!) { revokeSession(id: ${'$'}id) }
    """

    val REVOKE_OTHERS = """
        mutation RevokeOthers { revokeOtherSessions }
    """

    val CREATE_LOGIN_REQUEST = """
        $LOGIN_REQUEST_FIELDS
        mutation CreateLoginRequest(${'$'}deviceId: String, ${'$'}platform: String) {
            createLoginRequest(deviceId: ${'$'}deviceId, platform: ${'$'}platform) {
                request { ...LoginRequestFields }
                pollSecret
            }
        }
    """

    val ROTATE_LOGIN_TOKEN = """
        $LOGIN_REQUEST_FIELDS
        mutation RotateLoginToken(${'$'}id: Snowflake!, ${'$'}pollSecret: String!) {
            rotateLoginToken(id: ${'$'}id, pollSecret: ${'$'}pollSecret) { ...LoginRequestFields }
        }
    """

    val CLAIM_LOGIN_REQUEST = """
        mutation ClaimLogin(${'$'}qrToken: String!) {
            claimLoginRequest(qrToken: ${'$'}qrToken) {
                id ipAddress platform userAgent requestedAt
            }
        }
    """

    val APPROVE_LOGIN_REQUEST = """
        mutation ApproveLogin(${'$'}id: Snowflake!) { approveLoginRequest(id: ${'$'}id) }
    """

    val DENY_LOGIN_REQUEST = """
        mutation DenyLogin(${'$'}id: Snowflake!) { denyLoginRequest(id: ${'$'}id) }
    """

    val LOGIN_REQUEST_UPDATED = """
        $USER_FIELDS
        subscription OnLoginRequest(${'$'}id: Snowflake!, ${'$'}pollSecret: String!) {
            loginRequestUpdated(id: ${'$'}id, pollSecret: ${'$'}pollSecret) {
                status
                approvedBy { ...UserFields }
                auth {
                    accessToken refreshToken expiresInSeconds
                    user { ...UserFields }
                }
            }
        }
    """
}
