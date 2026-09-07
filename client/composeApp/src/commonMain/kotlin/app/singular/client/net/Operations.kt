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
            id username discriminator handle displayName avatarKey avatarUrl bannerKey
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
            mentions { type targetId }
            reactions { emoji count me }
        }
    """

    /**
     * `lastMessage` is selected narrowly on purpose — see [app.singular.client.net.LastMessageDto].
     * Pulling the full MessageFields here would make every sidebar load sign a presigned URL
     * per attachment for text nobody reads.
     */
    private const val CHANNEL_FIELDS = """
        fragment ChannelFields on Channel {
            id type name lastMessageId parentId
            members { ...UserFields }
            lastMessage {
                id content createdAt
                author { id username discriminator handle displayName }
                attachments { id kind }
            }
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

    // Feature 7. Android-only in practice — desktop notifies over the socket — but the
    // operation lives with the rest so the schema surface is in one file.
    val REGISTER_PUSH_TOKEN = """
        mutation RegisterPushToken(${'$'}platform: String!, ${'$'}token: String!, ${'$'}deviceId: String) {
            registerPushToken(platform: ${'$'}platform, token: ${'$'}token, deviceId: ${'$'}deviceId)
        }
    """

    val CREATE_GROUP_DM = """
        $USER_FIELDS
        $CHANNEL_FIELDS
        mutation CreateGroupDm(${'$'}userIds: [Snowflake!]!, ${'$'}name: String) {
            createGroupDm(userIds: ${'$'}userIds, name: ${'$'}name) { ...ChannelFields }
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
            ${'$'}displayName: String, ${'$'}bio: String, ${'$'}pronouns: String,
            ${'$'}accentColor: Int, ${'$'}avatarKey: String
        ) {
            updateProfile(
                displayName: ${'$'}displayName, bio: ${'$'}bio,
                pronouns: ${'$'}pronouns, accentColor: ${'$'}accentColor,
                avatarKey: ${'$'}avatarKey
            ) { ...UserFields }
        }
    """

    /**
     * Revokes the refresh token's whole rotation family, so signing out on this device really
     * does end the session rather than just forgetting it locally.
     */
    val LOGOUT = """
        mutation Logout(${'$'}token: String!) { logout(refreshToken: ${'$'}token) }
    """

    val CHANGE_USERNAME = """
        $USER_FIELDS
        mutation ChangeUsername(${'$'}username: String!) {
            changeUsername(username: ${'$'}username) { ...UserFields }
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

    /**
     * One stream for every channel you can read — what the desktop toast listens on.
     *
     * Separate from MESSAGE_CREATED because that one is scoped to the conversation you have
     * open, which is exactly the one you never want to be notified about.
     */
    val NOTIFICATIONS = """
        $USER_FIELDS
        $ATTACHMENT_FIELDS
        $MESSAGE_FIELDS
        subscription OnNotification { notifications { ...MessageFields } }
    """

    val ADD_REACTION = """
        $USER_FIELDS
        $ATTACHMENT_FIELDS
        $MESSAGE_FIELDS
        mutation AddReaction(${'$'}messageId: Snowflake!, ${'$'}emoji: String!) {
            addReaction(messageId: ${'$'}messageId, emoji: ${'$'}emoji) { ...MessageFields }
        }
    """

    val REMOVE_REACTION = """
        $USER_FIELDS
        $ATTACHMENT_FIELDS
        $MESSAGE_FIELDS
        mutation RemoveReaction(${'$'}messageId: Snowflake!, ${'$'}emoji: String!) {
            removeReaction(messageId: ${'$'}messageId, emoji: ${'$'}emoji) { ...MessageFields }
        }
    """

    /**
     * Live reaction snapshots for the open channel. The wire `me` reflects the actor, so the
     * client only reads the emoji/count here and recomputes its own reacted-state locally.
     */
    val REACTION_UPDATED = """
        subscription OnReaction(${'$'}channelId: Snowflake!) {
            reactionUpdated(channelId: ${'$'}channelId) {
                messageId channelId
                reactions { emoji count me }
            }
        }
    """

    /** Feature 12's inbox: every message that mentions you, newest first. */
    val MENTION_INBOX = """
        $USER_FIELDS
        $ATTACHMENT_FIELDS
        $MESSAGE_FIELDS
        query MentionInbox(${'$'}limit: Int) {
            mentionInbox(limit: ${'$'}limit) { ...MessageFields }
        }
    """

    val SET_CUSTOM_STATUS = """
        mutation SetCustomStatus(${'$'}text: String, ${'$'}emoji: String) {
            setCustomStatus(text: ${'$'}text, emoji: ${'$'}emoji) { userId status customText customEmoji }
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

/** Servers, roles and invites. Separate object so [Operations] stays about conversations. */
object GuildOperations {

    private const val USER_FIELDS = """
        fragment UserFields on User {
            id username discriminator handle displayName avatarKey avatarUrl bannerKey
            bio pronouns accentColor borderKey
            blockedByViewer
            presence { userId status customText customEmoji }
        }
    """

    private const val GUILD_FIELDS = """
        fragment GuildFields on Guild {
            id name iconKey iconUrl description ownerId myPermissions
            channels { id type name lastMessageId parentId }
            roles { id name color position permissions hoist mentionable isDefault }
            me {
                guildId nickname displayName
                roles { id name color position permissions hoist mentionable isDefault }
                user { ...UserFields }
            }
        }
    """

    private const val ROLE_FIELDS = """
        fragment RoleFields on Role {
            id name color position permissions hoist mentionable isDefault
        }
    """

    val GUILDS = """
        $USER_FIELDS
        $GUILD_FIELDS
        query Guilds { guilds { ...GuildFields } }
    """

    // Feature 18. Folders are a per-user view preference, so there is no membership to fetch
    // alongside them — one row of JSONB, read once at sign-in, written on drop.
    val FOLDERS = """
        query Folders { folders { folders { id name color guildIds } loose } }
    """

    val SAVE_FOLDERS = """
        mutation SaveFolders(${'$'}folders: [GuildFolderInput!]!, ${'$'}loose: [Snowflake!]!) {
            saveFolders(folders: ${'$'}folders, loose: ${'$'}loose) {
                folders { id name color guildIds }
                loose
            }
        }
    """

    val CREATE_GUILD = """
        $USER_FIELDS
        $GUILD_FIELDS
        mutation CreateGuild(${'$'}name: String!) {
            createGuild(name: ${'$'}name) { ...GuildFields }
        }
    """

    val CREATE_GUILD_CHANNEL = """
        mutation CreateGuildChannel(
            ${'$'}guildId: Snowflake!, ${'$'}name: String!,
            ${'$'}type: ChannelType, ${'$'}parentId: Snowflake
        ) {
            createGuildChannel(
                guildId: ${'$'}guildId, name: ${'$'}name, type: ${'$'}type, parentId: ${'$'}parentId
            ) {
                id type name lastMessageId parentId
            }
        }
    """

    val UPDATE_GUILD = """
        $USER_FIELDS
        $GUILD_FIELDS
        mutation UpdateGuild(
            ${'$'}id: Snowflake!, ${'$'}name: String, ${'$'}iconKey: String, ${'$'}description: String
        ) {
            updateGuild(id: ${'$'}id, name: ${'$'}name, iconKey: ${'$'}iconKey, description: ${'$'}description) {
                ...GuildFields
            }
        }
    """

    val INVITES = """
        query Invites(${'$'}guildId: Snowflake!) {
            invites(guildId: ${'$'}guildId) { code guildId uses maxUses }
        }
    """

    val GUILD_MEMBERS = """
        $USER_FIELDS
        query GuildMembers(${'$'}guildId: Snowflake!) {
            guildMembers(guildId: ${'$'}guildId) {
                guildId nickname displayName
                user { ...UserFields }
                roles { id name color position permissions hoist mentionable isDefault }
            }
        }
    """

    val CREATE_INVITE = """
        mutation CreateInvite(${'$'}guildId: Snowflake!) {
            createInvite(guildId: ${'$'}guildId) { code guildId uses maxUses }
        }
    """

    val REDEEM_INVITE = """
        $USER_FIELDS
        $GUILD_FIELDS
        mutation RedeemInvite(${'$'}code: String!) {
            redeemInvite(code: ${'$'}code) { ...GuildFields }
        }
    """

    val SET_NICKNAME = """
        mutation SetNickname(${'$'}guildId: Snowflake!, ${'$'}userId: Snowflake, ${'$'}nickname: String) {
            setNickname(guildId: ${'$'}guildId, userId: ${'$'}userId, nickname: ${'$'}nickname)
        }
    """

    val LEAVE_GUILD = """
        mutation LeaveGuild(${'$'}id: Snowflake!) { leaveGuild(id: ${'$'}id) }
    """

    // -- Roles ---------------------------------------------------------------

    val CREATE_ROLE = """
        $ROLE_FIELDS
        mutation CreateRole(${'$'}guildId: Snowflake!, ${'$'}name: String!, ${'$'}color: Int) {
            createRole(guildId: ${'$'}guildId, name: ${'$'}name, color: ${'$'}color) {
                ...RoleFields
            }
        }
    """

    val UPDATE_ROLE = """
        $ROLE_FIELDS
        mutation UpdateRole(
            ${'$'}roleId: Snowflake!, ${'$'}name: String, ${'$'}color: Int,
            ${'$'}hoist: Boolean, ${'$'}mentionable: Boolean
        ) {
            updateRole(
                roleId: ${'$'}roleId, name: ${'$'}name, color: ${'$'}color,
                hoist: ${'$'}hoist, mentionable: ${'$'}mentionable
            ) {
                ...RoleFields
            }
        }
    """

    val DELETE_ROLE = """
        mutation DeleteRole(${'$'}roleId: Snowflake!) { deleteRole(roleId: ${'$'}roleId) }
    """

    val ASSIGN_ROLE = """
        mutation AssignRole(
            ${'$'}guildId: Snowflake!, ${'$'}userId: Snowflake!, ${'$'}roleId: Snowflake!
        ) {
            assignRole(guildId: ${'$'}guildId, userId: ${'$'}userId, roleId: ${'$'}roleId)
        }
    """

    val UNASSIGN_ROLE = """
        mutation UnassignRole(
            ${'$'}guildId: Snowflake!, ${'$'}userId: Snowflake!, ${'$'}roleId: Snowflake!
        ) {
            unassignRole(guildId: ${'$'}guildId, userId: ${'$'}userId, roleId: ${'$'}roleId)
        }
    """

    // -- Moderation ----------------------------------------------------------

    val KICK_MEMBER = """
        mutation KickMember(${'$'}guildId: Snowflake!, ${'$'}userId: Snowflake!) {
            kickMember(guildId: ${'$'}guildId, userId: ${'$'}userId)
        }
    """

    val DELETE_INVITE = """
        mutation DeleteInvite(${'$'}code: String!) { deleteInvite(code: ${'$'}code) }
    """
}
