package app.singular.client

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import app.singular.client.net.ChannelDto
import app.singular.client.net.ChannelsData
import app.singular.client.net.GraphQlException
import app.singular.client.net.LoginData
import app.singular.client.net.MessageCreatedData
import app.singular.client.net.MessageDto
import app.singular.client.net.MessagesData
import app.singular.client.net.OpenDmData
import app.singular.client.net.Operations
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
import app.singular.client.net.SingularClient
import app.singular.client.net.StoryDto
import app.singular.client.net.StoryFeedData
import app.singular.client.platform.PickedFile
import app.singular.client.platform.pickFile
import app.singular.client.net.UnblockData
import app.singular.client.net.UnmuteChannelData
import app.singular.client.net.UpdateProfileData
import app.singular.client.net.UpdateSettingsData
import app.singular.client.net.UserSettingsDto
import app.singular.client.net.StartTypingData
import app.singular.client.net.TypingData
import app.singular.client.net.UserByHandleData
import app.singular.client.net.UserDto
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

    /** Feature 16. 0xRRGGBB, or null to use the app default. */
    var themePrimary by mutableStateOf<Int?>(null)
        private set
    var themeSecondary by mutableStateOf<Int?>(null)
        private set
    var themeDark by mutableStateOf<Boolean?>(null)
        private set
    var mutedChannels = mutableStateMapOf<String, Boolean>()
        private set

    /** 0f..1f while a file is uploading, null when idle. Drives the composer's progress bar. */
    var uploadProgress by mutableStateOf<Float?>(null)
        private set

    val stories = mutableStateListOf<StoryDto>()

    private var refreshToken: String? = null
    private var subscription: Job? = null
    private var typingSubscription: Job? = null
    private var presenceSubscription: Job? = null
    private var heartbeatJob: Job? = null
    private val typingExpiry = mutableMapOf<String, Job>()
    private var lastTypingSent = TimeSource.Monotonic.markNow() - TYPING_THROTTLE

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
        // TODO(phase 5): persist `refresh` to the OS keystore so sign-in survives a restart.
        // Plain preferences would be a credential written to disk in the clear.
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
                } catch (_: Exception) {
                    // A stale presence dot is not worth an error banner.
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
        }
    }

    /** Live status for a user, falling back to whatever came attached to their profile. */
    fun statusOf(user: UserDto): String = presence[user.id]?.status ?: user.status

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
     * Feature 16. Sends only what changed — the server treats a null field as "leave it", so a
     * colour change can't clobber a layout switch made a moment earlier on another device.
     */
    fun setTheme(primary: Int?, secondary: Int?, dark: Boolean?) = run {
        applySettings(
            client.execute<UpdateSettingsData>(
                Operations.UPDATE_SETTINGS,
                buildJsonObject {
                    put("input", buildJsonObject {
                        primary?.let { put("themePrimary", it) }
                        secondary?.let { put("themeSecondary", it) }
                        dark?.let { put("themeDark", it) }
                    })
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
    }

    fun openChannel(channel: ChannelDto) = run {
        selectedChannel = channel
        val vars = buildJsonObject {
            put("channelId", channel.id)
            put("limit", 50)
        }
        // The server returns newest-first for cursor pagination; the UI reads oldest-first.
        val page = client.execute<MessagesData>(Operations.MESSAGES, vars).messages.nodes.reversed()
        messages.clear()
        messages.addAll(page)

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
        openChannelNow(channel)
    }

    private suspend fun openChannelNow(channel: ChannelDto) {
        selectedChannel = channel
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
                } catch (e: Exception) {
                    error = "Reconnecting… (${describe(e)})"
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(30_000L)
            }
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
     * Posts a story from a picked image.
     *
     * Overlays go up as JSON and are composited by the client at view time — never baked into
     * the image, so a story stays restylable and a typo doesn't mean re-uploading.
     */
    fun postStory(overlaysJson: String = "[]") {
        scope.launch {
            try {
                val file = pickFile(imagesOnly = true) ?: return@launch
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
                    error = "Upload failed."
                    return@launch
                }

                uploadProgress = 0.8f
                client.execute<FinalizeUploadData>(
                    Operations.FINALIZE_UPLOAD,
                    buildJsonObject { put("attachmentId", slot.attachment.id) },
                )

                val story = client.execute<CreateStoryData>(
                    Operations.CREATE_STORY,
                    buildJsonObject {
                        put("attachmentId", slot.attachment.id)
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

    /** Snowflakes are unique, so id equality is the whole dedup story. */
    private fun appendIfNew(message: MessageDto) {
        // Sending is the clearest possible signal that someone stopped typing — drop their
        // indicator now rather than leaving it up for the rest of the timeout.
        typingExpiry.remove(message.author.id)?.cancel()
        typingUsers.remove(message.author.id)

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
