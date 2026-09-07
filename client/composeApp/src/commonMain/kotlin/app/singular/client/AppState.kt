package app.singular.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.singular.client.net.AttachmentBriefDto
import app.singular.client.net.AssignRoleData
import app.singular.client.net.ChannelDto
import app.singular.client.net.ChannelsData
import app.singular.client.net.CreateInviteData
import app.singular.client.net.CreateRoleData
import app.singular.client.net.DeleteInviteData
import app.singular.client.net.DeleteRoleData
import app.singular.client.net.GuildMemberDto
import app.singular.client.net.GuildMembersData
import app.singular.client.net.GraphQlException
import app.singular.client.net.InviteDto
import app.singular.client.net.InvitesData
import app.singular.client.net.KickMemberData
import app.singular.client.net.LastMessageDto
import app.singular.client.net.NotificationsData
import app.singular.client.net.AddReactionData
import app.singular.client.net.RemoveReactionData
import app.singular.client.net.ReactionDto
import app.singular.client.net.ReactionUpdateDto
import app.singular.client.net.ReactionUpdatedData
import app.singular.client.net.MentionInboxData
import app.singular.client.net.SetCustomStatusData
import app.singular.client.net.RoleDto
import app.singular.client.net.UnassignRoleData
import app.singular.client.net.UpdateGuildData
import app.singular.client.net.UpdateRoleData
import app.singular.client.net.isNewerSnowflake
import app.singular.client.platform.REFRESH_TOKEN_KEY
import app.singular.client.platform.clearSecret
import app.singular.client.platform.readLocalString
import app.singular.client.platform.readSecret
import app.singular.client.platform.writeLocalString
import app.singular.client.platform.showNotification
import app.singular.client.platform.writeSecret
import app.singular.client.net.LoginData
import app.singular.client.net.MessageCreatedData
import app.singular.client.net.MessageDto
import app.singular.client.net.MessagesData
import app.singular.client.net.OpenDmData
import app.singular.client.net.Operations
import app.singular.client.net.RefreshData
import app.singular.client.net.RegisterData
import app.singular.client.net.SendMessageData
import app.singular.client.net.BlockData
import app.singular.client.net.HeartbeatData
import app.singular.client.net.MuteChannelData
import app.singular.client.net.PresenceChangedData
import app.singular.client.net.PresenceDto
import app.singular.client.net.SetStatusData
import app.singular.client.net.SettingsData
import app.singular.client.net.AttachmentDto
import app.singular.client.net.CreateStoryData
import app.singular.client.net.CreateUploadData
import app.singular.client.net.FinalizeUploadData
import app.singular.client.net.SendLocationData
import app.singular.client.net.CreateGuildChannelData
import app.singular.client.net.CreateGuildData
import app.singular.client.net.CreateGroupDmData
import app.singular.client.net.FolderLayoutDto
import app.singular.client.net.FoldersData
import app.singular.client.net.GuildFolderDto
import app.singular.client.net.RailRow
import app.singular.client.net.SaveFoldersData
import app.singular.client.net.GuildDto
import app.singular.client.net.GuildOperations
import app.singular.client.net.GuildsData
import app.singular.client.net.RedeemInviteData
import app.singular.client.net.SingularClient
import app.singular.client.net.StoryDto
import app.singular.client.net.StoryFeedData
import app.singular.client.platform.PickedFile
import app.singular.client.platform.RecordedAudio
import app.singular.client.platform.pickFile
import app.singular.client.net.UnblockData
import app.singular.client.net.UnmuteChannelData
import app.singular.client.net.ChangeUsernameData
import app.singular.client.net.UpdateProfileData
import app.singular.client.net.UpdateSettingsData
import app.singular.client.net.UserSettingsDto
import app.singular.client.net.StartTypingData
import app.singular.client.net.TypingData
import app.singular.client.net.UserByHandleData
import app.singular.client.net.UserDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.TimeSource
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/**
 * Single source of UI truth.
 *
 * Deliberately one class rather than a view-model per screen: at phase-1 size the indirection
 * costs more than it buys, and the state genuinely is shared (the channel list, the signed-in
 * user, the live socket). Split it when guilds arrive and the surface actually diverges.
 */
class AppState(
    private val client: SingularClient,
    private val scope: CoroutineScope,
) {
    var currentUser by mutableStateOf<UserDto?>(null)
        private set
    /**
     * These are `val`, and that is load-bearing.
     *
     * Compose observes *reads of a SnapshotStateList's contents*. It does not observe
     * reassignment of a plain `var` — so `messages = newList()` swaps in a list nothing is
     * subscribed to, no recomposition is scheduled, and the UI keeps rendering the old one.
     * That looked like "the chat never loads, and messages I send only appear after a restart".
     *
     * Replace contents, never the instance.
     */
    val channels: SnapshotStateList<ChannelDto> = mutableStateListOf()
    val messages: SnapshotStateList<MessageDto> = mutableStateListOf()
    var selectedChannel by mutableStateOf<ChannelDto?>(null)
        private set

    /**
     * Surfaces a message the UI produced rather than the network.
     *
     * The one door into [error], so a platform failure (a denied microphone) reads exactly like
     * a failed request instead of needing its own display path in every screen that can see it.
     */
    fun reportError(message: String) {
        error = message
    }

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)

    /**
     * Who is currently typing here, keyed by user id.
     *
     * A map rather than a list so repeat notices from the same person just refresh their entry
     * instead of stacking up.
     */
    val typingUsers = mutableStateMapOf<String, UserDto>()

    /**
     * Live presence, keyed by user id, kept apart from the UserDto embedded in each message.
     *
     * A message carries whatever presence its author had when the message was fetched. Reading
     * status off that would freeze everyone's dot at the moment they last spoke, so the sidebar
     * consults this map instead and the subscription keeps it current.
     */
    val presence = mutableStateMapOf<String, PresenceDto>()

    /** Which of the two chat presentations to draw. Persisted server-side per user. */
    var chatLayout by mutableStateOf("BUBBLES")
        private set
    var myStatus by mutableStateOf("ONLINE")
        private set

    /**
     * Feature 16. The chosen appearance preset, or null for the app default.
     *
     * Presets replaced the two raw accent colours: a hue is not a theme, and deriving one from
     * two swatches produced something that was technically valid and visually incoherent. See
     * [app.singular.client.ui.Presets] for the reasoning.
     */
    var themePreset by mutableStateOf<String?>(null)
        private set

    /** 0xRRGGBB, or null. Legacy: only consulted when [themePreset] is null. */
    var themePrimary by mutableStateOf<Int?>(null)
        private set
    var themeSecondary by mutableStateOf<Int?>(null)
        private set

    /** Null follows the operating system. */
    var themeDark by mutableStateOf<Boolean?>(null)
        private set
    var mutedChannels = mutableStateMapOf<String, Boolean>()
        private set

    /** 0f..1f while a file is uploading, null when idle. Drives the composer's progress bar. */
    var uploadProgress by mutableStateOf<Float?>(null)
        private set

    val stories = mutableStateListOf<StoryDto>()

    /** Servers you're in. The rail on the far left. */
    val guilds = mutableStateListOf<GuildDto>()

    /**
     * Which server is open, or null for direct messages.
     *
     * Null is the DM "home" rather than a separate flag, so there is exactly one variable
     * describing where you are — two would drift and produce a sidebar showing a server's
     * channels while the header says Direct messages.
     */
    var selectedGuild by mutableStateOf<GuildDto?>(null)
        private set

    var lastInviteCode by mutableStateOf<String?>(null)

    /** Invite codes for the server whose settings are open. */
    val guildInvites = mutableStateListOf<InviteDto>()

    /**
     * Members of the server whose settings are open.
     *
     * Not on [GuildDto] on purpose: the guild list is fetched for every server on sign-in and
     * lives in the rail, and dragging a hundred members' full user objects through that fetch
     * would multiply its payload for a list the settings screen is the only consumer of.
     */
    val guildMembers = mutableStateListOf<GuildMemberDto>()

    /**
     * Newest message per channel, for the sidebar preview line.
     *
     * A map beside the channel list rather than a field mutated inside [ChannelDto], because
     * a new message has to update the preview *without* refetching the channel list — and
     * copying a DTO in a `SnapshotStateList` on every inbound message would rebuild every row
     * in the sidebar instead of the one that changed.
     *
     * Seeded from `Channel.lastMessage` on load, then kept current by [noteActivity].
     */
    val lastMessages = mutableStateMapOf<String, LastMessageDto>()

    /**
     * Channels with something unread, for the sidebar dot.
     *
     * Cleared by opening the channel. Deliberately client-side and not persisted: the server
     * has a read cursor (`markRead`), but wiring the badge to it belongs with the unread-count
     * work rather than being half-done here.
     */
    val unread = mutableStateMapOf<String, Boolean>()

    /**
     * How many unread messages in each channel are aimed at *you* — the red badge.
     *
     * Counted separately from [unread] because they answer different questions and deserve
     * different urgency: "something happened here" is a colour change you can ignore, "you
     * were asked something" is a number you cannot. Merging them would make every busy channel
     * look like it needed you.
     *
     * Whether a message counts is decided from the server's own `mentions` list rather than by
     * re-parsing the body here — the server already resolved roles and `@everyone` to decide
     * who to notify, and a second implementation would eventually disagree with it.
     */
    val mentionCounts = mutableStateMapOf<String, Int>()

    // -- Notification preferences -------------------------------------------
    //
    // Local, not server-side, and deliberately so: "pop a toast on this machine" is a property
    // of the machine. The same account on a work laptop and a phone wants different answers,
    // and syncing these would make choosing quiet on one device silence all of them.

    // Each is a backing snapshot field plus a property whose setter also persists, rather than
    // a `var` with a separate `setX()` function — Kotlin already emits `setNotifyEnabled` for
    // the `var`, so the pair collides on the JVM. This shape also means the call site is a
    // plain assignment and cannot forget to save.

    private var notifyEnabledState by mutableStateOf(readLocalString(NOTIFY_ENABLED) != "false")
    private var notifyMentionsOnlyState by mutableStateOf(readLocalString(NOTIFY_MENTIONS_ONLY) == "true")
    private var notifyPreviewsState by mutableStateOf(readLocalString(NOTIFY_PREVIEWS) != "false")

    /**
     * When on, screen transitions and entrance animations are skipped.
     *
     * Grouped with the notification prefs on purpose: like them, this is a property of the
     * person at this machine, not of the account — syncing it would move a vestibular-
     * sensitivity setting to devices where it doesn't apply.
     */
    private var reduceMotionState by mutableStateOf(readLocalString(REDUCE_MOTION) == "true")

    /** Skip transitions and entrance animations. */
    var reduceMotion: Boolean
        get() = reduceMotionState
        set(value) {
            reduceMotionState = value
            writeLocalString(REDUCE_MOTION, value.toString())
        }

    /** Master switch. Off means the app never raises a system notification. */
    var notifyEnabled: Boolean
        get() = notifyEnabledState
        set(value) {
            notifyEnabledState = value
            writeLocalString(NOTIFY_ENABLED, value.toString())
        }

    /** Only notify when you were actually addressed, rather than for every message. */
    var notifyMentionsOnly: Boolean
        get() = notifyMentionsOnlyState
        set(value) {
            notifyMentionsOnlyState = value
            writeLocalString(NOTIFY_MENTIONS_ONLY, value.toString())
        }

    /**
     * Whether the toast shows the message text.
     *
     * Off puts "New message" in place of the body. A notification is drawn over the lock
     * screen and into every screen-share, so this is a privacy control rather than a cosmetic
     * one — which is why it defaults to on but is one click from off.
     */
    var notifyPreviews: Boolean
        get() = notifyPreviewsState
        set(value) {
            notifyPreviewsState = value
            writeLocalString(NOTIFY_PREVIEWS, value.toString())
        }

    /**
     * The channel you were last reading in each server, so switching back lands where you left.
     *
     * A plain map, not snapshot state: nothing renders from it directly — it is only read at
     * the moment [openGuild] decides where to go — so making it observable would schedule
     * recompositions for a value no composable looks at.
     *
     * In memory only, and deliberately so for now: persisting it belongs with the read-cursor
     * work, where "where was I" is answered from the server and follows you between devices,
     * rather than being half-solved per install here.
     */
    private val lastChannelPerGuild = mutableMapOf<String, String>()

    private var refreshToken: String? = null
    private var subscription: Job? = null
    private var typingSubscription: Job? = null
    private var reactionSubscription: Job? = null
    private var presenceSubscription: Job? = null
    private var notificationSubscription: Job? = null
    private var heartbeatJob: Job? = null
    private val typingExpiry = mutableMapOf<String, Job>()
    private var lastTypingSent = TimeSource.Monotonic.markNow() - TYPING_THROTTLE

    /**
     * Members of the guild whose channel is open, for @-autocomplete.
     *
     * Loaded on channel open when the channel is a guild channel. Capped at 100 by the
     * server, which is every server this client is likely to see.
     */
    val channelMembers = mutableStateListOf<GuildMemberDto>()

    /** The mentions inbox — messages that pinged you, newest first. */
    val mentionInbox = mutableStateListOf<MessageDto>()

    val signedIn: Boolean get() = currentUser != null

    // -- Auth ---------------------------------------------------------------

    fun register(username: String, email: String, password: String) = run {
        val vars = buildJsonObject {
            put("input", buildJsonObject {
                put("username", username)
                put("email", email)
                put("password", password)
                put("deviceId", deviceId())
            })
        }
        val payload = client.execute<RegisterData>(Operations.REGISTER, vars).register
        adoptSession(payload.accessToken, payload.refreshToken, payload.user)
        startSession()
    }

    fun login(email: String, password: String) = run {
        val vars = buildJsonObject {
            put("input", buildJsonObject {
                put("email", email)
                put("password", password)
                put("deviceId", deviceId())
            })
        }
        val payload = client.execute<LoginData>(Operations.LOGIN, vars).login
        adoptSession(payload.accessToken, payload.refreshToken, payload.user)
        startSession()
    }

    /**
     * Adopts a session minted somewhere other than this screen — currently a QR approval.
     *
     * Same path as a password sign-in on purpose: the client must not care how the tokens were
     * obtained, or the two flows drift and only one of them gets the next fix.
     */
    suspend fun signInWithTokens(access: String, refresh: String, user: UserDto) {
        adoptSession(access, refresh, user)
        startSession()
    }

    private fun adoptSession(access: String, refresh: String, user: UserDto) {
        client.accessToken = access
        refreshToken = refresh
        currentUser = user
        // Seed the live presence map with our own, so the custom status shows before the
        // first presenceChanged event (which may be never — it only fires on change).
        user.presence?.let { presence[it.userId] = it }

        // Persisted so a relaunch doesn't ask for the password again. Written on every
        // adoption, not just the first, because refresh tokens are single-use and rotate —
        // storing only the original would leave a token that is already spent by the time
        // anyone tried to use it.
        writeSecret(REFRESH_TOKEN_KEY, refresh)
    }

    /**
     * Tries to restore the previous session from the stored refresh token.
     *
     * Returns true when it worked, so the UI can hold the splash rather than flashing the
     * login form and then replacing it a beat later.
     *
     * Any failure clears the stored token and reports false. That is the right response to
     * *every* failure here, not just an expired one: the server revokes a whole token family
     * when it sees a token reused, so a token that fails to exchange may well be one someone
     * else has already spent — and keeping it would retry a dead credential on every launch.
     */
    suspend fun tryRestoreSession(): Boolean {
        val stored = readSecret(REFRESH_TOKEN_KEY) ?: return false
        return try {
            val payload = client.execute<RefreshData>(
                Operations.REFRESH,
                buildJsonObject { put("token", stored) },
            ).refresh
            adoptSession(payload.accessToken, payload.refreshToken, payload.user)
            startSession()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            clearSecret(REFRESH_TOKEN_KEY)
            false
        }
    }

    // -- Channels and messages ----------------------------------------------

    /**
     * Everything that has to happen once, after a sign-in of any kind.
     *
     * Kept in one place so password sign-in and QR sign-in can't drift into loading different
     * things — a bug that only shows up on whichever path you test less.
     */
    private suspend fun startSession() {
        loadChannels()
        runCatching {
            val s = client.execute<SettingsData>(Operations.SETTINGS).settings
            applySettings(s)
        }
        currentUser?.let { myStatus = it.status }
        watchPresence()
        startHeartbeat()
        runCatching { loadStories() }
        runCatching { loadGuilds() }
        // After the servers: the arrangement refers to them by id, and a folder whose members
        // haven't loaded yet would render as an empty group.
        runCatching { loadFolders() }
        // Last: it subscribes to whatever channels and servers the two loads above found.
        watchNotifications()
    }

    /**
     * Keeps us marked online.
     *
     * The server treats a user with no recent heartbeat as offline regardless of what status
     * they chose, so this is what stops you going grey while you're sitting there reading.
     */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                runCatching { client.execute<HeartbeatData>(Operations.HEARTBEAT) }
                delay(HEARTBEAT_MS)
            }
        }
    }

    private fun watchPresence() {
        presenceSubscription?.cancel()
        presenceSubscription = scope.launch {
            var backoff = 1_000L
            while (true) {
                try {
                    client.subscribe(Operations.PRESENCE_CHANGED, buildJsonObject {})
                        .collect { data ->
                            backoff = 1_000L
                            val p = SingularClient.codec
                                .decodeFromJsonElement(PresenceChangedData.serializer(), data)
                                .presence
                            presence[p.userId] = p
                            if (p.userId == currentUser?.id) myStatus = p.status
                        }
                } catch (e: CancellationException) {
                    throw e   // shutting down, not failing — see watch()
                } catch (_: Exception) {
                    // A stale presence dot is not worth an error banner.
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    /** Live status for a user, falling back to whatever came attached to their profile. */
    fun statusOf(user: UserDto): String =
        presence[user.id]?.status ?: user.presence?.status ?: user.status

    /**
     * The custom status (emoji + text) for a user, or null — the sidebar/profile read this
     * directly off the live map so a change lands the moment the mutation returns.
     */
    fun customStatusOf(userId: String): String? {
        val p = presence[userId] ?: return null
        return listOfNotNull(p.customEmoji, p.customText?.takeIf { it.isNotBlank() })
            .joinToString(" ")
            .ifEmpty { null }
    }

    fun setStatus(status: String) = run {
        val p = client.execute<SetStatusData>(
            Operations.SET_STATUS,
            buildJsonObject { put("status", status) },
        ).presence
        myStatus = p.status
        presence[p.userId] = p
    }

    private fun applySettings(s: UserSettingsDto) {
        chatLayout = s.chatLayout
        themePreset = s.themePreset
        themePrimary = s.themePrimary
        themeSecondary = s.themeSecondary
        themeDark = s.themeDark
    }

    fun setLayout(layout: String) = run {
        applySettings(
            client.execute<UpdateSettingsData>(
                Operations.UPDATE_SETTINGS,
                buildJsonObject { put("input", buildJsonObject { put("chatLayout", layout) }) },
            ).settings
        )
    }

    /**
     * Feature 16. Switches appearance preset.
     *
     * Named `choose` rather than `set` because the class already has a private `themePreset`
     * setter, and `setThemePreset` collides with it on the JVM — a clash the compiler catches
     * but that reads as a mystery the first time.
     *
     * Sends only what changed — the server treats a null field as "leave it", so a theme change
     * can't clobber a layout switch made a moment earlier on another device.
     *
     * [preset] is a name, not null-to-clear: null already means "leave this field alone", so
     * "back to the default" has to be a real value. The default preset's own name ([Presets
     * .default.id]) is that value.
     */
    fun chooseThemePreset(preset: String) = run {
        applySettings(
            client.execute<UpdateSettingsData>(
                Operations.UPDATE_SETTINGS,
                buildJsonObject {
                    put("input", buildJsonObject { put("themePreset", preset) })
                },
            ).settings
        )
    }

    /** The dark/light override. Null would mean "leave it", so both states are sent explicitly. */
    fun setThemeDark(dark: Boolean) = run {
        applySettings(
            client.execute<UpdateSettingsData>(
                Operations.UPDATE_SETTINGS,
                buildJsonObject {
                    put("input", buildJsonObject { put("themeDark", dark) })
                },
            ).settings
        )
    }

    /** Feature 13. */
    fun saveProfile(displayName: String?, bio: String?, pronouns: String?, accentColor: Int?) = run {
        val updated = client.execute<UpdateProfileData>(
            Operations.UPDATE_PROFILE,
            buildJsonObject {
                displayName?.let { put("displayName", it) }
                bio?.let { put("bio", it) }
                pronouns?.let { put("pronouns", it) }
                accentColor?.let { put("accentColor", it) }
            },
        ).user
        currentUser = updated
    }

    /**
     * Renames the account.
     *
     * The discriminator is decided entirely by the server — it keeps your number when the pair
     * is free under the new name and draws a fresh one when it isn't. The client must not
     * predict it, so the returned user replaces the local copy wholesale.
     */
    fun changeUsername(username: String) = run {
        currentUser = client.execute<ChangeUsernameData>(
            Operations.CHANGE_USERNAME,
            buildJsonObject { put("username", username.trim()) },
        ).user
    }

    /**
     * Picks a picture and makes it the profile photo.
     *
     * Same three-step upload as any attachment, so an avatar gets the same EXIF stripping and
     * thumbnailing as anything else off a user's disk. Only the finalized attachment id is
     * stored on the profile; the server signs a URL for it on read.
     */
    fun uploadAvatar(file: PickedFile) {
        scope.launch {
            try {
                uploadProgress = 0f

                val slot = client.execute<CreateUploadData>(
                    Operations.CREATE_UPLOAD,
                    buildJsonObject {
                        put("filename", file.name)
                        put("contentType", file.contentType)
                        put("sizeBytes", file.sizeBytes.toString())
                        put("voiceNote", false)
                    },
                ).slot

                uploadProgress = 0.3f
                if (!client.putBytes(slot.uploadUrl, file.bytes, file.contentType)) {
                    error = "Upload failed. Check the storage service is running."
                    return@launch
                }

                uploadProgress = 0.8f
                client.execute<FinalizeUploadData>(
                    Operations.FINALIZE_UPLOAD,
                    buildJsonObject { put("attachmentId", slot.attachment.id) },
                )

                currentUser = client.execute<UpdateProfileData>(
                    Operations.UPDATE_PROFILE,
                    buildJsonObject { put("avatarKey", slot.attachment.id) },
                ).user
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                uploadProgress = null
            }
        }
    }

    /**
     * Signs out.
     *
     * Tears down every live subscription before clearing the user, in that order. Clearing
     * first would drop the UI to the login screen while sockets carried on delivering into a
     * state nobody is showing — and the reconnect loops would keep running with a token that
     * is about to be revoked.
     */
    fun signOut() {
        subscription?.cancel()
        typingSubscription?.cancel()
        presenceSubscription?.cancel()
        notificationSubscription?.cancel()
        heartbeatJob?.cancel()
        clearTyping()

        val token = refreshToken
        scope.launch {
            // Best effort. A failed revoke must not strand someone on a screen they asked to
            // leave — the access token expires shortly anyway.
            runCatching {
                token?.let {
                    client.postRaw(Operations.LOGOUT, buildJsonObject { put("token", it) })
                }
            }
        }

        client.accessToken = null
        refreshToken = null
        // The stored token is revoked server-side by the logout above; leaving the file behind
        // would mean the next launch silently tries a dead credential.
        clearSecret(REFRESH_TOKEN_KEY)
        channels.clear()
        messages.clear()
        guilds.clear()
        stories.clear()
        lastMessages.clear()
        unread.clear()
        mentionCounts.clear()
        mutedChannels.clear()
        presence.clear()
        selectedChannel = null
        selectedGuild = null
        error = null
        currentUser = null
    }

    fun blockUser(userId: String) = run {
        client.execute<BlockData>(Operations.BLOCK_USER, buildJsonObject { put("userId", userId) })
        // Refetch rather than patch in place: blocking changes authorBlocked on every message
        // this person wrote, and re-deriving that client-side would be guessing.
        selectedChannel?.let { openChannelNow(it) }
        loadChannels()
    }

    fun unblockUser(userId: String) = run {
        client.execute<UnblockData>(Operations.UNBLOCK_USER, buildJsonObject { put("userId", userId) })
        selectedChannel?.let { openChannelNow(it) }
        loadChannels()
    }

    fun toggleMute(channelId: String) = run {
        val muted = mutedChannels[channelId] == true
        if (muted) {
            client.execute<UnmuteChannelData>(
                Operations.UNMUTE_CHANNEL, buildJsonObject { put("channelId", channelId) })
        } else {
            client.execute<MuteChannelData>(
                Operations.MUTE_CHANNEL, buildJsonObject { put("channelId", channelId) })
        }
        mutedChannels[channelId] = !muted
    }

    private suspend fun loadChannels() {
        val loaded = client.execute<ChannelsData>(Operations.CHANNELS).channels
        channels.clear()
        channels.addAll(loaded)

        // Seed the previews. Anything already newer in the map — a message that arrived
        // between the request and the response — wins, so a refresh can't roll the sidebar
        // backwards to a message that has since been superseded.
        loaded.forEach { channel ->
            val fresh = channel.lastMessage ?: return@forEach
            val existing = lastMessages[channel.id]
            if (existing == null || isNewerSnowflake(fresh.id, existing.id)) {
                lastMessages[channel.id] = fresh
            }
        }
    }

    fun openChannel(channel: ChannelDto) = run {
        selectedChannel = channel
        unread.remove(channel.id)
        mentionCounts.remove(channel.id)
        val vars = buildJsonObject {
            put("channelId", channel.id)
            put("limit", 50)
        }
        // The server returns newest-first for cursor pagination; the UI reads oldest-first.
        val page = client.execute<MessagesData>(Operations.MESSAGES, vars).messages.nodes.reversed()
        messages.clear()
        messages.addAll(page)

        // The @-autocomplete source: guild channels need the server's member list; DMs already
        // carry theirs on the channel. Loaded after the messages so a slow members fetch can
        // never delay the conversation appearing.
        channelMembers.clear()
        selectedGuild?.let { guild ->
            if (guild.channels.any { it.id == channel.id }) loadChannelMembers(guild.id)
        }

        watch(channel.id)
    }

    /**
     * Tells the server we're typing, at most once per [TYPING_THROTTLE].
     *
     * Called on every keystroke by the UI, so throttling here rather than in the composable
     * keeps the rule in one place — a mutation per character would be absurd, and the indicator
     * has multi-second granularity anyway.
     */
    fun onTyping() {
        val channel = selectedChannel ?: return
        if (lastTypingSent.elapsedNow() < TYPING_THROTTLE) return
        lastTypingSent = TimeSource.Monotonic.markNow()

        scope.launch {
            // Best effort. A failed typing notice is not worth showing anyone an error over.
            runCatching {
                client.execute<StartTypingData>(
                    Operations.START_TYPING,
                    buildJsonObject { put("channelId", channel.id) },
                )
            }
        }
    }

    /**
     * Expires each person's indicator on a timer.
     *
     * There is no "stopped typing" event by design — typing usually ends with a closed window
     * or a dropped connection, neither of which sends anything. Waiting for a stop event is how
     * indicators get stuck on forever.
     */
    private fun noteTyping(user: UserDto) {
        typingUsers[user.id] = user
        typingExpiry.remove(user.id)?.cancel()
        typingExpiry[user.id] = scope.launch {
            delay(TYPING_TTL_MS)
            typingUsers.remove(user.id)
            typingExpiry.remove(user.id)
        }
    }

    private fun clearTyping() {
        typingExpiry.values.forEach { it.cancel() }
        typingExpiry.clear()
        typingUsers.clear()
    }

    fun openDmWithHandle(handle: String) = run {
        val name = handle.substringBeforeLast('#').trim()
        val discriminator = handle.substringAfterLast('#').trim().toIntOrNull()
            ?: throw IllegalArgumentException("Handles look like alex#0971")

        val found = client.execute<UserByHandleData>(
            Operations.USER_BY_HANDLE,
            buildJsonObject {
                put("username", name)
                put("discriminator", discriminator)
            },
        ).userByHandle ?: throw IllegalArgumentException("No one goes by $handle")

        val channel = client.execute<OpenDmData>(
            Operations.OPEN_DM,
            buildJsonObject { put("userId", found.id) },
        ).channel

        loadChannels()
        // The new conversation is not in the notification stream the server resolved when the
        // socket opened, so without this the first message in a brand-new DM would arrive
        // silently until the next relaunch.
        watchNotifications()
        openChannelNow(channel)
    }

    /**
     * Feature 2's missing half: create a group DM and land in it.
     *
     * Mirrors [openDmWithHandle] deliberately — new channel, same reload/resubscribe/open
     // sequence — because a group DM differs from a DM in *who* is in it, not in what
     * happens next.
     */
    fun createGroupDm(userIds: List<String>, name: String?) = run {
        val channel = client.execute<CreateGroupDmData>(
            Operations.CREATE_GROUP_DM,
            buildJsonObject {
                put("userIds", buildJsonArray { userIds.forEach { add(it) } })
                name?.let { put("name", it) }
            },
        ).channel

        loadChannels()
        watchNotifications()
        openChannelNow(channel)
    }

    /**
     * Leaves the conversation without leaving the app — what Escape does.
     *
     * Tears the subscriptions down rather than leaving them running behind an empty pane: a
     * closed conversation that keeps streaming is how you end up with a dozen live sockets
     * after ten minutes of clicking around.
     */
    fun closeChannel() {
        selectedChannel = null
        messages.clear()
        channelMembers.clear()
        subscription?.cancel()
        typingSubscription?.cancel()
        reactionSubscription?.cancel()
        clearTyping()
    }

    private suspend fun openChannelNow(channel: ChannelDto) {
        selectedChannel = channel
        unread.remove(channel.id)
        mentionCounts.remove(channel.id)
        val page = client.execute<MessagesData>(
            Operations.MESSAGES,
            buildJsonObject { put("channelId", channel.id); put("limit", 50) },
        ).messages.nodes.reversed()
        messages.clear()
        messages.addAll(page)
        watch(channel.id)
    }

    fun send(text: String) {
        val channel = selectedChannel ?: return
        val body = text.trim()
        if (body.isEmpty()) return

        scope.launch {
            try {
                val vars = buildJsonObject {
                    put("input", buildJsonObject {
                        put("channelId", channel.id)
                        put("content", body)
                        // Idempotency key: if the reply is lost to a flaky connection, resending
                        // this exact nonce returns the original message instead of a duplicate.
                        put("nonce", newNonce())
                    })
                }
                val sent = client.execute<SendMessageData>(Operations.SEND_MESSAGE, vars).message
                // Show it immediately, unconditionally. The subscription echoes it back too,
                // but appendIfNew dedupes on the snowflake — so the message appears even when
                // the socket is down, instead of waiting on an echo that may never arrive.
                appendIfNew(sent)
            } catch (e: Exception) {
                error = describe(e)
            }
        }
    }

    /**
     * Watches a channel, reconnecting with backoff.
     *
     * Phase 2 replaces this with the resume protocol — the client tracks a sequence number and
     * the gateway replays the gap on reconnect. Until then a dropped socket means refetching
     * the page, which is why this also reloads on reconnect.
     */
    private fun watch(channelId: String) {
        clearTyping()
        watchTyping(channelId)
        watchReactions(channelId)
        subscription?.cancel()
        subscription = scope.launch {
            var backoff = 1_000L
            while (true) {
                try {
                    client.subscribe(
                        Operations.MESSAGE_CREATED,
                        buildJsonObject { put("channelId", channelId) },
                    ).collect { data ->
                        backoff = 1_000L
                        val message = SingularClient.codec
                            .decodeFromJsonElement(MessageCreatedData.serializer(), data)
                            .message
                        if (message.channelId == selectedChannel?.id) appendIfNew(message)
                    }
                } catch (e: CancellationException) {
                    // Cancellation is not a failure. It is how leaving a channel, signing out
                    // or closing the app *stops* this loop — and CancellationException is an
                    // Exception, so a bare `catch (e: Exception)` reported the shutdown as a
                    // connection error and left "Reconnecting…" pinned to a healthy screen.
                    throw e
                } catch (e: Exception) {
                    error = "Reconnecting… (${describe(e)})"
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    /**
     * Live reaction snapshots for the open channel.
     *
     * The wire `me` flag reflects the actor, not this viewer, so it is ignored on arrival:
     * only the emoji+count are taken from the event, and the viewer's own reacted-state is
     * recomputed by merging the snapshot into the local message. An optimistic toggle that's
     * still in flight will be corrected by the next snapshot for it.
     */
    private fun watchReactions(channelId: String) {
        reactionSubscription?.cancel()
        reactionSubscription = scope.launch {
            var backoff = 1_000L
            while (true) {
                try {
                    client.subscribe(
                        Operations.REACTION_UPDATED,
                        buildJsonObject { put("channelId", channelId) },
                    ).collect { data ->
                        backoff = 1_000L
                        val update = SingularClient.codec
                            .decodeFromJsonElement(ReactionUpdatedData.serializer(), data).update
                        if (update.channelId == selectedChannel?.id) applyReactionUpdate(update)
                    }
                } catch (e: CancellationException) {
                    throw e   // shutting down, not failing — see watch()
                } catch (_: Exception) {
                    // Same policy as typing: a stale chip is not worth a banner.
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    /**
     * Merges a reaction snapshot into the open channel's message list.
     *
     * `me` is dropped from the wire data (see [watchReactions]); if this viewer reacted, the
     * optimistic toggle already flipped their flag and any drift is corrected by the snapshot's
     * count. If another user reacted, the chip appears/updates.
     */
    private fun applyReactionUpdate(update: ReactionUpdateDto) {
        val index = messages.indexOfFirst { it.id == update.messageId }
        if (index == -1) return
        val message = messages[index]
        val mine = message.reactions.filter { it.me }.map { it.emoji }.toSet()
        messages[index] = message.copy(
            reactions = update.reactions.map { it.copy(me = it.emoji in mine) }
        )
    }

    /**
     * Toggles the viewer's reaction on a message, optimistically.
     *
     * The action is decided BEFORE the optimistic flip — reading "did I react" after the flip
     * would always see the post-change answer and invert the mutation. Optimistic-first
     * because a reaction must feel instant; the subscription echo corrects any drift.
     */
    fun toggleReaction(messageId: String, emoji: String) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index == -1) return

        val message = messages[index]
        val existing = message.reactions.firstOrNull { it.emoji == emoji }
        val shouldAdd = existing == null || !existing.me

        messages[index] = if (shouldAdd) {
            if (existing == null) {
                message.copy(reactions = message.reactions + ReactionDto(emoji, 1, true))
            } else {
                message.copy(
                    reactions = message.reactions.map {
                        if (it.emoji == emoji) it.copy(count = it.count + 1, me = true) else it
                    }
                )
            }
        } else {
            message.copy(
                reactions = message.reactions
                    .map { if (it.emoji == emoji) it.copy(count = it.count - 1, me = false) else it }
                    .filter { it.count > 0 }
            )
        }

        scope.launch {
            try {
                if (shouldAdd) {
                    client.execute<AddReactionData>(
                        Operations.ADD_REACTION,
                        buildJsonObject { put("messageId", messageId); put("emoji", emoji) },
                    )
                } else {
                    client.execute<RemoveReactionData>(
                        Operations.REMOVE_REACTION,
                        buildJsonObject { put("messageId", messageId); put("emoji", emoji) },
                    )
                }
            } catch (e: Exception) {
                error = describe(e)
            }
        }
    }

    /** Loads the mentions inbox. */
    fun loadMentionInbox() = run {
        val loaded = client.execute<MentionInboxData>(
            Operations.MENTION_INBOX,
            buildJsonObject { put("limit", 50) },
        ).messages
        mentionInbox.clear()
        mentionInbox.addAll(loaded)
    }

    /**
     * Sets an emoji + text custom status (feature 4's half-built corner: the server field and
     * plumbing existed, the UI never surfaced it).
     */
    fun setCustomStatus(emoji: String?, text: String?) = run {
        val updated = client.execute<SetCustomStatusData>(
            Operations.SET_CUSTOM_STATUS,
            buildJsonObject {
                put("text", text)
                put("emoji", emoji)
            },
        ).presence
        presence[updated.userId] = updated
        currentUser = currentUser?.copy(presence = updated)
    }

    /**
     * Loads the member list for the guild whose channel is open — the autocomplete source.
     *
     * Only called for guild channels; DMs use the channel's own member list.
     */
    private suspend fun loadChannelMembers(guildId: String) {
        try {
            val loaded = client.execute<GuildMembersData>(
                GuildOperations.GUILD_MEMBERS,
                buildJsonObject { put("guildId", guildId) },
            ).guildMembers
            channelMembers.clear()
            channelMembers.addAll(loaded)
        } catch (_: Exception) {
            // Autocomplete falls back to empty — better than blocking the chat on it.
        }
    }

    private fun watchTyping(channelId: String) {
        typingSubscription?.cancel()
        typingSubscription = scope.launch {
            var backoff = 1_000L
            while (true) {
                try {
                    client.subscribe(
                        Operations.TYPING,
                        buildJsonObject { put("channelId", channelId) },
                    ).collect { data ->
                        backoff = 1_000L
                        val event = SingularClient.codec
                            .decodeFromJsonElement(TypingData.serializer(), data).event
                        if (event.channelId == selectedChannel?.id) noteTyping(event.user)
                    }
                } catch (e: CancellationException) {
                    throw e   // shutting down, not failing — see watch()
                } catch (_: Exception) {
                    // Silent: a missing typing indicator is not worth a banner.
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    // -- Media (feature 6) ---------------------------------------------------

    /**
     * Picks a file, uploads it, and sends it as a message.
     *
     * Three round trips, in this order and no other:
     *
     *   1. `createUpload`   - the server mints a row and signs a PUT for exactly this file
     *   2. PUT to storage   - direct; the bytes never touch the app server
     *   3. `finalizeUpload` - the server verifies the object, strips EXIF, makes a thumbnail
     *
     * Only after step 3 does the attachment id mean anything, which is why sending happens
     * last rather than optimistically alongside the upload.
     */
    fun attachAndSend(caption: String = "", imagesOnly: Boolean = false) {
        val channel = selectedChannel ?: return

        scope.launch {
            try {
                val file = pickFile(imagesOnly) ?: return@launch   // cancelled, not an error
                uploadProgress = 0f

                val slot = client.execute<CreateUploadData>(
                    Operations.CREATE_UPLOAD,
                    buildJsonObject {
                        put("filename", file.name)
                        put("contentType", file.contentType)
                        put("sizeBytes", file.sizeBytes.toString())
                        put("voiceNote", false)
                    },
                ).slot

                uploadProgress = 0.15f
                if (!client.putBytes(slot.uploadUrl, file.bytes, file.contentType)) {
                    error = "Upload failed. Check the storage service is running."
                    uploadProgress = null
                    return@launch
                }

                uploadProgress = 0.75f
                val ready = client.execute<FinalizeUploadData>(
                    Operations.FINALIZE_UPLOAD,
                    buildJsonObject { put("attachmentId", slot.attachment.id) },
                ).attachment

                val sent = client.execute<SendMessageData>(
                    Operations.SEND_MESSAGE,
                    buildJsonObject {
                        put("input", buildJsonObject {
                            put("channelId", channel.id)
                            put("content", caption)
                            put("nonce", newNonce())
                            put("attachmentIds", buildJsonArray { add(ready.id) })
                        })
                    },
                ).message

                appendIfNew(sent)
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                uploadProgress = null
            }
        }
    }

    /**
     * Sends a voice note: the second half of feature 6.
     *
     * The upload shape already carried `voiceNote`, `durationMs` and `waveform` — this is the
     * first caller that supplies all three. Duration and peaks are what let the receiver draw a
     * waveform without downloading and decoding the audio, which is the whole reason the
     * columns exist rather than being derived server-side.
     */
    fun recordAndSend(audio: RecordedAudio) {
        val channel = selectedChannel ?: return

        scope.launch {
            try {
                uploadProgress = 0f
                val filename = "voice.${if (audio.mimeType == "audio/mp4") "m4a" else "wav"}"

                val slot = client.execute<CreateUploadData>(
                    Operations.CREATE_UPLOAD,
                    buildJsonObject {
                        put("filename", filename)
                        put("contentType", audio.mimeType)
                        put("sizeBytes", audio.bytes.size.toString())
                        put("voiceNote", true)
                    },
                ).slot

                uploadProgress = 0.15f
                if (!client.putBytes(slot.uploadUrl, audio.bytes, audio.mimeType)) {
                    error = "Upload failed. Check the storage service is running."
                    return@launch
                }

                uploadProgress = 0.75f
                val ready = client.execute<FinalizeUploadData>(
                    Operations.FINALIZE_UPLOAD,
                    buildJsonObject {
                        put("attachmentId", slot.attachment.id)
                        put("durationMs", audio.durationMs)
                        put("waveform", buildJsonArray { audio.peaks.forEach { add(it) } })
                    },
                ).attachment

                val sent = client.execute<SendMessageData>(
                    Operations.SEND_MESSAGE,
                    buildJsonObject {
                        put("input", buildJsonObject {
                            put("channelId", channel.id)
                            put("content", "")
                            put("nonce", newNonce())
                            put("attachmentIds", buildJsonArray { add(ready.id) })
                        })
                    },
                ).message

                appendIfNew(sent)
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                uploadProgress = null
            }
        }
    }

    /** Shares a fixed point. Live location needs a platform location provider; not wired yet. */
    fun sendLocation(latitude: Double, longitude: Double, label: String?) = run {
        val channel = selectedChannel ?: return@run
        val sent = client.execute<SendLocationData>(
            Operations.SEND_LOCATION,
            buildJsonObject {
                put("channelId", channel.id)
                put("latitude", latitude)
                put("longitude", longitude)
                label?.let { put("label", it) }
            },
        ).message
        appendIfNew(sent)
    }

    // -- Stories (features 5 and 20) -----------------------------------------

    fun loadStories() = run {
        val loaded = client.execute<StoryFeedData>(Operations.STORY_FEED).stories
        stories.clear()
        stories.addAll(loaded)
    }

    /**
     * Opens the picker and hands the bytes back, without uploading anything.
     *
     * Split out from [postStory] because the editor needs the picture *before* the story is
     * posted — it has to draw a live preview, and you have to be able to change your mind
     * about the photo without having already uploaded the last one. Uploading on pick would
     * leave an orphaned attachment behind every time someone re-chose.
     */
    fun pickStoryImage(onPicked: (PickedFile) -> Unit) = pickImage(onPicked)

    /**
     * Opens the picker and hands the bytes back, uploading nothing.
     *
     * Every image entry point goes through this now, because picking and uploading had to come
     * apart: an avatar is cropped between the two, and a story preview is drawn from the bytes
     * long before anything is sent. Uploading on pick would also orphan an attachment on the
     * server every time someone changed their mind about which photo to use.
     */
    fun pickImage(onPicked: (PickedFile) -> Unit) {
        scope.launch {
            // Cancelling is not an error and must not surface one.
            runCatching { pickFile(imagesOnly = true) }.getOrNull()?.let(onPicked)
        }
    }

    /**
     * Posts a story — text-only, or a photo with overlays.
     *
     * [image] null means a text story: nothing is uploaded and the server stores the
     * background id instead of an attachment. That is the whole difference between the two
     * kinds, which is why one function covers both rather than two that drift apart.
     *
     * Overlays go up as JSON and are composited by the client at view time — never baked into
     * the image, so a story stays restylable and a typo doesn't mean re-uploading.
     */
    fun postStory(
        overlaysJson: String = "[]",
        backgroundId: String? = null,
        image: PickedFile? = null,
    ) {
        scope.launch {
            try {
                uploadProgress = if (image == null) null else 0f

                // Three round trips for a photo — sign, PUT, finalize — and none at all for
                // text. See attachAndSend for why the order matters.
                val attachmentId = image?.let { file ->
                    val slot = client.execute<CreateUploadData>(
                        Operations.CREATE_UPLOAD,
                        buildJsonObject {
                            put("filename", file.name)
                            put("contentType", file.contentType)
                            put("sizeBytes", file.sizeBytes.toString())
                            put("voiceNote", false)
                        },
                    ).slot

                    uploadProgress = 0.3f
                    if (!client.putBytes(slot.uploadUrl, file.bytes, file.contentType)) {
                        error = "Upload failed. Check the storage service is running."
                        return@launch
                    }

                    uploadProgress = 0.8f
                    client.execute<FinalizeUploadData>(
                        Operations.FINALIZE_UPLOAD,
                        buildJsonObject { put("attachmentId", slot.attachment.id) },
                    )
                    slot.attachment.id
                }

                val story = client.execute<CreateStoryData>(
                    Operations.CREATE_STORY,
                    buildJsonObject {
                        attachmentId?.let { put("attachmentId", it) }
                        backgroundId?.let { put("background", it) }
                        put("overlays", overlaysJson)
                    },
                ).story
                stories.add(0, story)
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                uploadProgress = null
            }
        }
    }

    fun markStorySeen(storyId: String) {
        scope.launch {
            runCatching {
                client.postRaw(
                    Operations.MARK_STORY_SEEN,
                    buildJsonObject { put("id", storyId) },
                )
            }
            // Reflect it locally rather than refetching the whole tray for one boolean.
            val index = stories.indexOfFirst { it.id == storyId }
            if (index >= 0) stories[index] = stories[index].copy(seen = true)
        }
    }

    // -- Servers (features 3, 10, 14, 18, 19) --------------------------------

    suspend fun loadGuilds() {
        val loaded = client.execute<GuildsData>(GuildOperations.GUILDS).guilds
        guilds.clear()
        guilds.addAll(loaded)

        // Keep the open server pointing at the freshly loaded copy, or drop it if we were
        // removed. Holding a stale DTO would show channels we can no longer read.
        selectedGuild = selectedGuild?.let { current -> loaded.firstOrNull { it.id == current.id } }
    }

    // -- Feature 18: server folders -------------------------------------------

    /**
     * The rail's arrangement: folders plus the loose servers in drag order.
     *
     * Per-user, stored as one JSONB row, and — critically — *not* the order [guilds] is in.
     * [guilds] is the server's membership order and is replaced on every reload; this is the
     * arrangement the user chose and is the rail's source of truth for what to draw.
     */
    var folderLayout by mutableStateOf(FolderLayoutDto())
        private set

    /** Collapsed folders. Session-only: which groups you folded is a glance-level preference. */
    private val collapsedFolders = mutableSetOf<String>()

    suspend fun loadFolders() {
        folderLayout = runCatching { client.execute<FoldersData>(GuildOperations.FOLDERS).folders }
            .getOrDefault(FolderLayoutDto())
    }

    /**
     * The rail's rows, in draw order: a header and (when expanded) its tiles for each folder,
     * then the unfiled servers.
     *
     * Servers inside a collapsed folder are drawn as a single stack tile, which is the entire
     * point of folding one — the rail keeps its height instead of growing with every group.
     */
    fun railRows(): List<RailRow> = buildList {
        val byId = guilds.associateBy { it.id }

        folderLayout.folders.forEach { folder ->
            val members = folder.guildIds.mapNotNull { byId[it] }
            if (members.isEmpty()) return@forEach    // a folder whose servers are all gone
            val collapsed = folder.id in collapsedFolders
            add(RailRow.Folder(folder, members, collapsed))
            if (!collapsed) members.forEach { add(RailRow.Guild(it, folderId = folder.id)) }
        }

        // Loose servers last. Anything filed nowhere — including servers you joined since the
        // arrangement was last saved — appears here rather than vanishing from the rail.
        val filed = folderLayout.folders.flatMap { it.guildIds }.toSet()
        val loose = folderLayout.loose.mapNotNull { byId[it] }.filter { it.id !in filed } +
            guilds.filter { it.id !in folderLayout.loose && it.id !in filed }
        loose.forEach { add(RailRow.Guild(it, folderId = null)) }
    }

    /**
     * Flat draw order of every server, for the Ctrl+digit and Alt+arrow shortcuts.
     *
     * Collapsed folders count as their first server — that's the tile you'd tap — so the
     * shortcuts stay in step with the strip even when several servers are folded away.
     */
    fun railGuildOrder(): List<GuildDto> = buildList {
        railRows().forEach { row ->
            when (row) {
                is RailRow.Folder -> {
                    if (row.collapsed) addAll(row.members.take(1)) else addAll(row.members)
                }
                is RailRow.Guild -> add(row.guild)
            }
        }
    }.distinctBy { it.id }

    fun toggleFolderCollapsed(folderId: String) {
        if (!collapsedFolders.remove(folderId)) collapsedFolders.add(folderId)
    }

    /**
     * Applies an arrangement the rail computed and persists it.
     *
     * Debounced by the caller — the rail settles the order locally so the drag feels instant,
     * then saves once rather than writing JSONB on every pointer move.
     */
    fun saveFolders(folders: List<GuildFolderDto>, loose: List<String>) = run {
        val saved = client.execute<SaveFoldersData>(
            GuildOperations.SAVE_FOLDERS,
            buildJsonObject {
                put("folders", buildJsonArray {
                    folders.forEach { folder ->
                        add(buildJsonObject {
                            put("id", folder.id)
                            folder.name?.let { put("name", it) }
                            folder.color?.let { put("color", it) }
                            put("guildIds", buildJsonArray { folder.guildIds.forEach { add(it) } })
                        })
                    }
                })
                put("loose", buildJsonArray { loose.forEach { add(it) } })
            },
        ).save
        folderLayout = saved
    }

    /** Creates a folder containing exactly one server, and puts it where it was. */
    fun createFolderFor(guildId: String, folderId: String = newFolderId()): List<GuildFolderDto> =
        folderLayout.folders + GuildFolderDto(
            id = folderId,
            name = null,
            color = null,
            guildIds = listOf(guildId),
        )

    private fun newFolderId(): String = "f${System.currentTimeMillis().toString(36)}"

    /**
     * Files a server into an existing folder, then reorders: the folder moves to where the
     * server was dropped, because that is where the user's attention was.
     */
    fun fileGuildInFolder(guildId: String, folderId: String) {
        val folders = folderLayout.folders.toMutableList()
        val index = folders.indexOfFirst { it.id == folderId }
        if (index < 0) return
        val target = folders[index]
        if (guildId in target.guildIds) return

        folders[index] = target.copy(guildIds = target.guildIds + guildId)
        // Out of wherever it was first: a server in two folders would render twice.
        val loose = folderLayout.loose.filter { it != guildId }
        val others = folders.map {
            if (it.id == folderId) it else it.copy(guildIds = it.guildIds.filter { id -> id != guildId })
        }
        saveFolderArrangement(others, loose)
    }

    /**
     * Puts two servers in one folder. Dropping A on B means "these belong together" — the
     * folder is created when neither has one, and extended when one already does.
     */
    fun groupGuilds(draggedId: String, targetId: String) {
        // A drop on itself with nothing else in play: make a folder of one. That's the
        // "start a new group here" gesture, and it gives the user something to drop into.
        if (draggedId == targetId) {
            saveFolderArrangement(
                createFolderFor(draggedId),
                folderLayout.loose.filter { it != draggedId },
            )
            return
        }

        val folders = folderLayout.folders.toMutableList()
        val existing = folders.indexOfFirst { draggedId in it.guildIds || targetId in it.guildIds }

        if (existing >= 0) {
            // One of them is already filed: the other joins it.
            val folder = folders[existing]
            val merged = folder.guildIds + listOf(draggedId, targetId).filter { it !in folder.guildIds }
            folders[existing] = folder.copy(guildIds = merged)
            saveFolderArrangement(
                folders,
                folderLayout.loose.filter { it != draggedId && it != targetId },
            )
        } else {
            // Neither is filed: a new folder holding both. Its servers drop out of the loose
            // list, which is the only place an unfiled server is drawn from.
            saveFolderArrangement(
                createFolderFor(draggedId).map {
                    if (it.guildIds == listOf(draggedId)) it.copy(guildIds = listOf(draggedId, targetId))
                    else it
                },
                folderLayout.loose.filter { it != draggedId && it != targetId },
            )
        }
    }

    fun removeGuildFromFolder(guildId: String, folderId: String) {
        val folders = folderLayout.folders.map { folder ->
            if (folder.id == folderId) folder.copy(guildIds = folder.guildIds.filter { it != guildId })
            else folder
        }.filter { it.guildIds.isNotEmpty() }
        // Back to the loose list, at the end — nowhere else in particular to put it.
        saveFolderArrangement(folders, folderLayout.loose + guildId)
    }

    fun renameFolder(folderId: String, name: String) {
        val folders = folderLayout.folders.map {
            if (it.id == folderId) it.copy(name = name.trim().ifEmpty { null }) else it
        }
        saveFolderArrangement(folders, folderLayout.loose)
    }

    /**
     * Deletes the folder and keeps its servers — deleting a view should never feel like it
     * might have deleted anything real, so they return to the loose list in their folder order.
     */
    fun deleteFolder(folderId: String) {
        val folder = folderLayout.folders.firstOrNull { it.id == folderId } ?: return
        val folders = folderLayout.folders.filter { it.id != folderId }
        saveFolderArrangement(folders, folderLayout.loose + folder.guildIds)
    }

    /**
     * Writes an arrangement through, locally first.
     *
     * Local-first is the whole feel of the feature: the rail has already moved by the time the
     * pointer lifted, and this only reconciles with the server's answer. A failure leaves the
     * optimistic order in place and surfaces the error — worse than a refetch would be a rail
     * that snaps back under someone's finger.
     */
    private fun saveFolderArrangement(folders: List<GuildFolderDto>, loose: List<String>) {
        val dedupedLoose = loose.distinct()
        folderLayout = FolderLayoutDto(folders = folders.filter { it.guildIds.isNotEmpty() }, loose = dedupedLoose)
        scope.launch {
            runCatching { saveFolders(folders, dedupedLoose) }
                .onFailure { error = describe(it as Exception) }
        }
    }

    /**
     * Switches to a server, or back to direct messages when [guild] is null.
     *
     * Clears the open conversation deliberately: a DM left selected while a server is showing
     * would put someone else's private conversation on screen under a server's header.
     */
    fun openGuild(guild: GuildDto?) {
        // Remember where you were in the server you're leaving, before anything is cleared.
        selectedGuild?.let { previous ->
            selectedChannel?.let { open -> lastChannelPerGuild[previous.id] = open.id }
        }

        selectedGuild = guild
        selectedChannel = null
        messages.clear()
        channelMembers.clear()
        subscription?.cancel()
        typingSubscription?.cancel()
        reactionSubscription?.cancel()
        clearTyping()

        // Walk back into the channel you were last reading here. A server that dumps you on an
        // empty pane every time makes you re-navigate on every switch, and the one thing you
        // reliably want is the conversation you just left.
        guild?.let { target ->
            val remembered = lastChannelPerGuild[target.id]
                ?.let { id -> target.channels.firstOrNull { it.id == id } }
            // Falling back to the first text channel rather than nothing: on a server you have
            // never opened there is no memory to honour, and an empty pane is not an answer.
            val landing = remembered ?: target.channels.firstOrNull { it.type == "GUILD_TEXT" }
            landing?.let(::openChannel)
        }
    }

    fun createGuild(name: String) = run {
        val guild = client.execute<CreateGuildData>(
            GuildOperations.CREATE_GUILD,
            buildJsonObject { put("name", name.trim()) },
        ).guild
        loadGuilds()
        selectedGuild = guilds.firstOrNull { it.id == guild.id }
        selectedChannel = null
    }

    fun createGuildChannel(
        guildId: String,
        name: String,
        type: String = "GUILD_TEXT",
        parentId: String? = null,
    ) = run {
        client.execute<CreateGuildChannelData>(
            GuildOperations.CREATE_GUILD_CHANNEL,
            buildJsonObject {
                put("guildId", guildId)
                put("name", name.trim())
                put("type", type)
                parentId?.let { put("parentId", it) }
            },
        )
        loadGuilds()
        watchNotifications()
    }

    /** Server settings: rename, re-describe, or re-icon. Nulls are left as they are. */
    fun updateGuild(
        guildId: String,
        name: String? = null,
        description: String? = null,
        iconKey: String? = null,
    ) = run {
        client.execute<UpdateGuildData>(
            GuildOperations.UPDATE_GUILD,
            buildJsonObject {
                put("id", guildId)
                name?.let { put("name", it.trim()) }
                description?.let { put("description", it.trim()) }
                iconKey?.let { put("iconKey", it) }
            },
        )
        loadGuilds()
    }

    /**
     * Picks an image and makes it the server icon.
     *
     * Runs the same three-step upload as an attachment — sign, PUT, finalize — so the icon
     * gets the same EXIF stripping and thumbnailing as anything else that arrives from a
     * user's disk. Only the finalized attachment id is stored on the guild.
     */
    fun uploadGuildIcon(guildId: String, file: PickedFile) {
        scope.launch {
            try {
                uploadProgress = 0f

                val slot = client.execute<CreateUploadData>(
                    Operations.CREATE_UPLOAD,
                    buildJsonObject {
                        put("filename", file.name)
                        put("contentType", file.contentType)
                        put("sizeBytes", file.sizeBytes.toString())
                        put("voiceNote", false)
                    },
                ).slot

                uploadProgress = 0.3f
                if (!client.putBytes(slot.uploadUrl, file.bytes, file.contentType)) {
                    error = "Upload failed. Check the storage service is running."
                    return@launch
                }

                uploadProgress = 0.8f
                client.execute<FinalizeUploadData>(
                    Operations.FINALIZE_UPLOAD,
                    buildJsonObject { put("attachmentId", slot.attachment.id) },
                )

                client.execute<UpdateGuildData>(
                    GuildOperations.UPDATE_GUILD,
                    buildJsonObject { put("id", guildId); put("iconKey", slot.attachment.id) },
                )
                loadGuilds()
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                uploadProgress = null
            }
        }
    }

    /** Every invite code for a server, so settings can list them rather than mint a new one. */
    fun loadInvites(guildId: String) = run {
        val loaded = client.execute<InvitesData>(
            GuildOperations.INVITES,
            buildJsonObject { put("guildId", guildId) },
        ).invites
        guildInvites.clear()
        guildInvites.addAll(loaded)
    }

    fun createInvite(guildId: String) = run {
        val invite = client.execute<CreateInviteData>(
            GuildOperations.CREATE_INVITE,
            buildJsonObject { put("guildId", guildId) },
        ).invite
        lastInviteCode = invite.code
        guildInvites.add(0, invite)
    }

    fun redeemInvite(code: String) = run {
        val guild = client.execute<RedeemInviteData>(
            GuildOperations.REDEEM_INVITE,
            buildJsonObject { put("code", code.trim()) },
        ).guild
        loadGuilds()
        // A server just joined isn't in the notification stream the server resolved earlier.
        watchNotifications()
        selectedGuild = guilds.firstOrNull { it.id == guild.id }
    }

    fun setNickname(guildId: String, userId: String? = null, nickname: String?) = run {
        client.postRaw(
            GuildOperations.SET_NICKNAME,
            buildJsonObject {
                put("guildId", guildId)
                userId?.let { put("userId", it) }
                nickname?.let { put("nickname", it) }
            },
        )
        loadGuilds()
        // The member list shows the old nickname until the next visit otherwise. Guild data
        // alone isn't enough: `me` only carries your own.
        if (userId != null) loadGuildMembers(guildId)
    }

    /**
     * The server's members, for the settings screen's Members section.
     *
     * Capped at 100 by the server; enough for every server this client is likely to see, and
     * the alternative is pagination UI for a list whose 101st entry nobody has met yet.
     */
    fun loadGuildMembers(guildId: String) = run {
        val loaded = client.execute<GuildMembersData>(
            GuildOperations.GUILD_MEMBERS,
            buildJsonObject { put("guildId", guildId) },
        ).guildMembers
        guildMembers.clear()
        guildMembers.addAll(loaded)
    }

    fun kickMember(guildId: String, userId: String) = run {
        client.execute<KickMemberData>(
            GuildOperations.KICK_MEMBER,
            buildJsonObject {
                put("guildId", guildId)
                put("userId", userId)
            },
        )
        loadGuildMembers(guildId)
    }

    fun createRole(guildId: String, name: String, color: Int? = null) = run {
        client.execute<CreateRoleData>(
            GuildOperations.CREATE_ROLE,
            buildJsonObject {
                put("guildId", guildId)
                put("name", name)
                color?.let { put("color", it) }
            },
        )
        loadGuilds()
    }

    fun updateRole(
        roleId: String,
        name: String? = null,
        color: Int? = null,
        hoist: Boolean? = null,
        mentionable: Boolean? = null,
    ) = run {
        client.execute<UpdateRoleData>(
            GuildOperations.UPDATE_ROLE,
            buildJsonObject {
                put("roleId", roleId)
                name?.let { put("name", it) }
                color?.let { put("color", it) }
                hoist?.let { put("hoist", it) }
                mentionable?.let { put("mentionable", it) }
            },
        )
        loadGuilds()
    }

    fun deleteRole(roleId: String) = run {
        client.execute<DeleteRoleData>(
            GuildOperations.DELETE_ROLE,
            buildJsonObject { put("roleId", roleId) },
        )
        loadGuilds()
    }

    /** Assign or remove a role from a member, in one function because the UI is one toggle. */
    fun setMemberRole(guildId: String, userId: String, roleId: String, assigned: Boolean) = run {
        if (assigned) {
            client.execute<AssignRoleData>(
                GuildOperations.ASSIGN_ROLE,
                buildJsonObject {
                    put("guildId", guildId)
                    put("userId", userId)
                    put("roleId", roleId)
                },
            )
        } else {
            client.execute<UnassignRoleData>(
                GuildOperations.UNASSIGN_ROLE,
                buildJsonObject {
                    put("guildId", guildId)
                    put("userId", userId)
                    put("roleId", roleId)
                },
            )
        }
        loadGuildMembers(guildId)
    }

    fun deleteInvite(code: String) = run {
        client.execute<DeleteInviteData>(
            GuildOperations.DELETE_INVITE,
            buildJsonObject { put("code", code) },
        )
        guildInvites.removeAll { it.code == code }
    }

    fun leaveGuild(guildId: String) = run {
        client.postRaw(GuildOperations.LEAVE_GUILD, buildJsonObject { put("id", guildId) })
        if (selectedGuild?.id == guildId) openGuild(null)
        loadGuilds()
    }

    // -- Keyboard navigation -------------------------------------------------

    /**
     * The list Alt+Up / Alt+Down walks: a server's text channels, or your conversations.
     *
     * One property so the shortcut, the sidebar and the "which is selected" check can never
     * disagree about what the list *is* — a stepper walking a different list from the one on
     * screen skips rows for no visible reason.
     */
    private val navigableChannels: List<ChannelDto>
        get() = selectedGuild?.channels?.filter { it.type == "GUILD_TEXT" } ?: channels

    /**
     * Moves [delta] places through that list.
     *
     * Clamped rather than wrapped. Wrapping means holding Alt+Down past the end silently
     * teleports you back to the top, and in a list you are scanning that reads as the shortcut
     * having lost your place.
     */
    fun stepChannel(delta: Int) {
        val list = navigableChannels
        if (list.isEmpty()) return

        val current = list.indexOfFirst { it.id == selectedChannel?.id }
        // From nothing selected, Down opens the first and Up opens the last.
        val next =
            if (current < 0) (if (delta > 0) 0 else list.lastIndex)
            else (current + delta).coerceIn(0, list.lastIndex)

        if (next != current) openChannel(list[next])
    }

    /** Steps through the rail. Index -1 is home (direct messages), so it is part of the walk. */
    /**
     * Alt+arrow between servers. Walks the **rail's** order, not the membership list: with
     * folders, those differ, and a shortcut that disagrees with what's on screen is broken.
     */
    fun stepGuild(delta: Int) {
        val order = railGuildOrder()
        val current = order.indexOfFirst { it.id == selectedGuild?.id }
        // -1 is "direct messages", the row above the first server — matching Ctrl+0.
        val next = (current + delta).coerceIn(-1, order.lastIndex)
        if (next < 0) openGuild(null) else openGuild(order[next])
    }

    /**
     * Ctrl+1..9 and Ctrl+0. Out-of-range indexes do nothing rather than jumping to an end.
     *
     * Numbering follows the rail — a collapsed folder counts as the server you'd land on —
     * because the numbers a user memorises are the ones the strip teaches them.
     */
    fun openGuildAt(index: Int) {
        val order = railGuildOrder()
        when {
            index < 0 -> openGuild(null)
            index <= order.lastIndex -> openGuild(order[index])
            // Ctrl+7 in a four-server list is a mistake, not a request for the last one.
            else -> Unit
        }
    }

    // -- Notifications -------------------------------------------------------

    /**
     * Reports this device's push token to the server — feature 7's registration half.
     *
     * Nothing calls this yet. The server side (registration, the mute/DND filter, the outbox and
     * the transports) is built and provider-agnostic; the only thing missing is a device token,
     * and the only way to get an Android one is Firebase, which this project doesn't vendor.
     * [platform] is a parameter rather than a constant for exactly that reason — Web Push gets
     * its subscription from the browser with no SDK at all, so this is the seam where whichever
     * provider comes first plugs in.
     */
    fun registerPushToken(platform: String, token: String) {
        if (token.isBlank()) return
        scope.launch {
            runCatching {
                client.postRaw(
                    Operations.REGISTER_PUSH_TOKEN,
                    buildJsonObject {
                        put("platform", platform)
                        put("token", token)
                        put("deviceId", deviceId())
                    },
                )
            }
        }
    }

    /**
     * One socket carrying messages from every channel, so conversations you don't have open
     * can still update the sidebar and raise a toast.
     *
     * The server resolves your channel list when the subscription opens, so this is restarted
     * whenever that list changes — after loading channels, opening a DM, or joining a server.
     * Restarting is cheap (one socket) and is the only thing that makes a brand-new
     * conversation notify without a relaunch.
     */
    private fun watchNotifications() {
        notificationSubscription?.cancel()
        notificationSubscription = scope.launch {
            var backoff = 1_000L
            while (true) {
                try {
                    client.subscribe(Operations.NOTIFICATIONS, buildJsonObject { }).collect { data ->
                        backoff = 1_000L
                        val message = SingularClient.codec
                            .decodeFromJsonElement(NotificationsData.serializer(), data).message
                        onNotified(message)
                    }
                } catch (e: CancellationException) {
                    throw e   // shutting down, not failing — see watch()
                } catch (_: Exception) {
                    // Silent. A dropped notification socket must not put a red banner over a
                    // conversation that is working perfectly well on its own subscription.
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    /**
     * What to do with a message from the notification stream.
     *
     * The sidebar preview updates for every channel; the toast is suppressed for the
     * conversation already on screen and for muted channels. Those are separate decisions on
     * purpose — you still want the preview line to be current in a channel you muted.
     */
    private fun onNotified(message: MessageDto) {
        noteActivity(message)

        // Blocked authors are silent by definition — feature 11 would be meaningless if the
        // person you blocked could still make your machine chime.
        if (message.authorBlocked) return
        if (message.channelId == selectedChannel?.id) return
        if (mutedChannels[message.channelId] == true) return

        unread[message.channelId] = true
        val addressed = addressesMe(message)
        if (addressed) {
            mentionCounts[message.channelId] = (mentionCounts[message.channelId] ?: 0) + 1
        }

        // The badges above are updated regardless — those are ambient state, and silencing
        // notifications should not also blind the sidebar. Only the toast is suppressed.
        if (!notifyEnabled) return
        if (notifyMentionsOnly && !addressed) return

        val channel = channels.firstOrNull { it.id == message.channelId }
            ?: guilds.flatMap { it.channels }.firstOrNull { it.id == message.channelId }

        // In a server channel the title alone ("general") says nothing about who spoke, so the
        // author goes in the heading there and the channel name after it.
        val title = when {
            channel == null -> message.author.label
            channel.type == "GUILD_TEXT" -> "${message.author.label} · #${channel.name.orEmpty()}"
            channel.type == "GROUP_DM" -> "${message.author.label} · ${channel.title(currentUser?.id)}"
            else -> message.author.label
        }

        showNotification(
            title,
            // "New message" instead of the text when previews are off — a toast is drawn over
            // the lock screen and into every screen-share.
            if (notifyPreviews) previewOf(message) else "New message",
        )
    }

    /**
     * Whether a message is addressed to the viewer: by name, by a role they hold, or by a
     * broadcast.
     *
     * Reads the server's resolved `mentions` list, not the message body. The server has
     * already done this work to decide who to notify, and it knows things the client would
     * have to guess at — so re-deriving it here would eventually produce a badge that
     * disagrees with the notification that was actually sent.
     */
    private fun addressesMe(message: MessageDto): Boolean {
        val me = currentUser?.id ?: return false

        // Roles are per-server, so a ROLE mention only counts if it names a role you hold in
        // the server that channel belongs to.
        val myRoles = guilds
            .firstOrNull { g -> g.channels.any { it.id == message.channelId } }
            ?.me?.roles?.map { it.id }.orEmpty().toSet()

        return message.mentions.any { mention ->
            when (mention.type) {
                "USER" -> mention.targetId == me
                "ROLE" -> mention.targetId in myRoles
                "EVERYONE", "HERE" -> true
                else -> false
            }
        }
    }

    /**
     * Unread and mention state rolled up to a server, for the rail.
     *
     * Computed from the channel maps rather than tracked separately: one source of truth means
     * opening a channel clears the server's dot for free, instead of needing a second
     * bookkeeping path that can drift out of step with the first.
     */
    fun guildHasUnread(guild: GuildDto): Boolean =
        guild.channels.any { unread[it.id] == true }

    fun guildMentionCount(guild: GuildDto): Int =
        guild.channels.sumOf { mentionCounts[it.id] ?: 0 }

    /** The same rollup for a category: what its children are carrying while it is collapsed. */
    fun categoryHasUnread(children: List<ChannelDto>): Boolean =
        children.any { unread[it.id] == true }

    fun categoryMentionCount(children: List<ChannelDto>): Int =
        children.sumOf { mentionCounts[it.id] ?: 0 }

    /** The same one-line summary the sidebar shows, reused so the toast can't disagree with it. */
    private fun previewOf(message: MessageDto): String =
        message.content?.replace('\n', ' ')?.trim().orEmpty().ifEmpty {
            when (message.attachments.firstOrNull()?.kind) {
                "IMAGE" -> "Photo"
                "VIDEO" -> "Video"
                "VOICE_NOTE" -> "Voice message"
                "AUDIO" -> "Audio"
                null -> "Shared a location"
                else -> "Attachment"
            }
        }

    /**
     * Records a message as a channel's newest, for the sidebar preview.
     *
     * Guarded on id order rather than "last write wins": the same message can arrive twice —
     * once from the channel subscription and once from the notification stream — and a
     * reconnect can replay an older one behind a newer one.
     */
    private fun noteActivity(message: MessageDto) {
        val existing = lastMessages[message.channelId]
        if (existing != null && !isNewerSnowflake(message.id, existing.id)) return

        lastMessages[message.channelId] = LastMessageDto(
            id = message.id,
            content = message.content,
            createdAt = message.createdAt,
            author = message.author,
            attachments = message.attachments.map { AttachmentBriefDto(it.id, it.kind) },
        )
    }

    /** Snowflakes are unique, so id equality is the whole dedup story. */
    private fun appendIfNew(message: MessageDto) {
        // Sending is the clearest possible signal that someone stopped typing — drop their
        // indicator now rather than leaving it up for the rest of the timeout.
        typingExpiry.remove(message.author.id)?.cancel()
        typingUsers.remove(message.author.id)

        noteActivity(message)
        if (messages.none { it.id == message.id }) messages.add(message)
    }

    fun dismissError() { error = null }

    // -- Plumbing ------------------------------------------------------------

    /** Runs a suspending action with the busy flag and one uniform error path. */
    private fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                block()
            } catch (e: Exception) {
                error = describe(e)
            } finally {
                busy = false
            }
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is GraphQlException -> e.message ?: "Something went wrong."
        else -> e.message ?: e::class.simpleName ?: "Something went wrong."
    }

    private fun newNonce(): String =
        (1..16).map { NONCE_ALPHABET[Random.nextInt(NONCE_ALPHABET.length)] }.joinToString("")

    private companion object {
        const val NOTIFY_ENABLED = "notify_enabled"
        const val NOTIFY_MENTIONS_ONLY = "notify_mentions_only"
        const val NOTIFY_PREVIEWS = "notify_previews"
        const val REDUCE_MOTION = "reduce_motion"

        const val NONCE_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        /** At most one typing mutation per this long, however fast someone types. */
        val TYPING_THROTTLE = kotlin.time.Duration.parse("3s")

        /** How long an indicator survives without a fresh notice. Comfortably over the throttle,
         *  so a steady typist never flickers. */
        const val TYPING_TTL_MS = 7_000L

        /** Comfortably inside the server's 60s presence timeout, with room for one to be lost. */
        const val HEARTBEAT_MS = 25_000L
    }
}

/**
 * Stable per-install identifier.
 *
 * NOT a MAC address: those don't survive the first router hop, browsers have no API for them,
 * Android 10+ returns a fixed dummy and iOS blocks them entirely. A random UUID in the OS
 * keystore is what actually gives you device continuity.
 */
expect fun deviceId(): String

/**
 * Human-readable platform label, e.g. "Windows desktop".
 *
 * Shown on the approving device's confirmation screen and in the sessions list, so it has to
 * read like something a person recognises — not a user-agent string.
 */
expect val platformName: String
