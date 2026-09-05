package app.singular.client

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import app.singular.client.net.SingularClient
import app.singular.client.ui.buildImageLoader
import app.singular.client.ui.ChatScreen
import app.singular.client.ui.KeyboardScope
import app.singular.client.ui.ShortcutsDialog
import app.singular.client.ui.handleGlobalShortcut
import app.singular.client.ui.isPress
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import app.singular.client.ui.LoginScreen
import app.singular.client.ui.MentionsScreen
import app.singular.client.ui.ServerSettingsScreen
import app.singular.client.ui.ServerSettingsSection
import app.singular.client.ui.SessionsScreen
import app.singular.client.ui.SettingsScreen
import app.singular.client.ui.StoriesScreen
import app.singular.client.ui.SingularTheme
import app.singular.client.ui.Presets

@Composable
fun App(
    httpUrl: String = SingularClient.DEFAULT_HTTP,
    wsUrl: String = SingularClient.DEFAULT_WS,
    /**
     * Chrome drawn above everything, inside the theme.
     *
     * The desktop's custom title bar comes through here rather than wrapping [App] from the
     * outside, because a bar outside `SingularTheme` reads Material's defaults instead of the
     * user's palette — which is the entire reason for replacing the OS one.
     */
    titleBar: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val client = remember(httpUrl, wsUrl) { SingularClient(httpUrl, wsUrl) }
    val state = remember(client) { AppState(client, scope) }
    val sessions = remember(client) { SessionState(client, scope) }

    // QR sign-in funnels into exactly the same adoption path as a password login, so the two
    // flows can't drift apart and leave only one of them fixed.
    val qr = remember(client) {
        QrLoginState(
            client = client,
            scope = scope,
            platform = platformName,
            onSignedIn = state::signInWithTokens,
        )
    }

    var showSessions by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showStories by remember { mutableStateOf(false) }
    var showMentions by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }

    // Restoring the stored session. Starts true so the very first frame is the spinner, not
    // the login form — the check is a network round trip and would otherwise flash.
    var restoring by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        // The result is ignored on purpose: success flips `signedIn`, failure leaves it false,
        // and either way this screen is done waiting.
        runCatching { state.tryRestoreSession() }
        restoring = false
    }

    /**
     * Server settings, if open: which server, and which section it opened on.
     *
     * The guild is resolved from `state.guilds` at render time rather than captured here —
     * `loadGuilds()` replaces every GuildDto after each settings save, and a captured one would
     * freeze the screen on pre-save data. Storing the id and looking it up means a rename in
     * Overview is on screen the moment it's saved.
     */
    var serverSettingsGuildId by remember { mutableStateOf<String?>(null) }
    var serverSettingsSection by remember { mutableStateOf(ServerSettingsSection.OVERVIEW) }

    /**
     * What "back" means, in one place.
     *
     * Every screen calls this rather than flipping its own flag, so Escape, a back arrow and a
     * Done button can never disagree about where they land. The order is the order things were
     * stacked: the help sheet is on top of any screen, and a screen is on top of the chat.
     *
     * Returns false when there is nothing left to go back to, which is what lets the caller
     * pass the key on instead of swallowing it.
     */
    val goBack: () -> Boolean = {
        when {
            showShortcuts -> { showShortcuts = false; true }
            showSettings -> { showSettings = false; true }
            showStories -> { showStories = false; true }
            showMentions -> { showMentions = false; true }
            showSessions -> { showSessions = false; true }
            serverSettingsGuildId != null -> { serverSettingsGuildId = null; true }
            else -> false   // ChatScreen handles its own layer: closing the conversation
        }
    }

    /** Opening one destination closes the others, so screens can't stack invisibly. */
    fun go(target: String?) {
        showSettings = target == "settings"
        showStories = target == "stories"
        showSessions = target == "sessions"
        showMentions = target == "mentions"
        // Server settings is not a Ctrl-chord destination, but opening one of those from
        // inside it should still leave it behind — same rule as the screens above.
        if (target != null) serverSettingsGuildId = null
    }

    /** Server settings is opened for *this* selected server, straight at the section asked for. */
    fun openServerSettings(section: ServerSettingsSection) {
        val guild = state.selectedGuild ?: return
        go(null)
        serverSettingsSection = section
        serverSettingsGuildId = guild.id
    }

    DisposableEffect(client) { onDispose { client.close() } }

    // Installed once for the whole app. Coil resolves this through a singleton, so building a
    // loader per screen would throw away the memory cache on every navigation and re-download
    // images the app already had.
    val platformContext = LocalPlatformContext.current
    remember(platformContext) {
        SingletonImageLoader.setSafe { buildImageLoader(platformContext) }
        true
    }

    // Feature 16. A preset if they chose one; otherwise their legacy raw accents if they have
    // any from before presets existed; otherwise the default theme. That ordering is what keeps
    // an existing user's saved colours from being discarded by an upgrade.
    //
    // themeDark stays null to follow the OS unless they overrode it.
    val dark = state.themeDark ?: isSystemInDarkTheme()
    val hasPreset = state.themePreset != null

    SingularTheme(
        preset = Presets.byId(state.themePreset),
        dark = dark,
        // Supplied only when there is no preset, so they can never both be in play at once.
        legacyPrimary = if (hasPreset) null else state.themePrimary,
        legacySecondary = if (hasPreset) null else state.themeSecondary,
    ) {
      Column(Modifier.fillMaxSize()) {
        titleBar()
        Surface(Modifier.weight(1f).fillMaxWidth()) {
            // One attempt to pick up the stored session, before anything is drawn. Holding a
            // spinner for it rather than showing the login form first is the difference
            // between "the app remembered me" and a form that flashes up and vanishes.
            if (restoring) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Surface
            }

            if (!state.signedIn) {
                // No shell shortcuts before sign-in: there is nowhere to navigate to, and a
                // stray Ctrl+D on a login form should do nothing rather than something.
                LoginScreen(state, qr)
                return@Surface
            }

            if (showShortcuts) ShortcutsDialog { showShortcuts = false }

            // The shell's own key layer, wrapping every signed-in screen. Escape unwinds one
            // level; the Ctrl chords jump between destinations from wherever you are.
            KeyboardScope(
                // Which screen is showing. Changing it re-takes keyboard focus, so Escape
                // keeps working after navigating to a screen that has nothing focusable on it.
                refocusKey = listOf(showSettings, showStories, showMentions, showSessions, serverSettingsGuildId),
                onPreviewKey = { event ->
                    when {
                        event.isPress && event.key == Key.Escape -> goBack()
                        handleGlobalShortcut(
                            event,
                            onSettings = { go(if (showSettings) null else "settings") },
                            onSessions = { go(if (showSessions) null else "sessions") },
                            onStories = { go(if (showStories) null else "stories") },
                            onMentions = { go(if (showMentions) null else "mentions") },
                            onHelp = { showShortcuts = !showShortcuts },
                        ) -> true
                        else -> false
                    }
                }
            ) {
                // Resolved per composition: `loadGuilds()` replaces the DTOs, so holding one
                // would show stale data after the first save. A guild that vanished from the
                // list — left, kicked — falls through to the chat, which is where you are.
                val serverSettingsGuild = serverSettingsGuildId
                    ?.let { id -> state.guilds.firstOrNull { it.id == id } }

                when {
                    showSettings -> SettingsScreen(state) { showSettings = false }
                    showStories -> StoriesScreen(state) { showStories = false }
                    showMentions -> MentionsScreen(state) { showMentions = false }
                    showSessions -> SessionsScreen(sessions) { showSessions = false }
                    serverSettingsGuild != null -> ServerSettingsScreen(
                        state = state,
                        guild = serverSettingsGuild,
                        initialSection = serverSettingsSection,
                        onClose = { serverSettingsGuildId = null },
                    )
                    else -> ChatScreen(
                        state,
                        onOpenSessions = { go("sessions") },
                        onOpenSettings = { go("settings") },
                        onOpenStories = { go("stories") },
                        onOpenMentions = { go("mentions") },
                        onOpenServerSettings = ::openServerSettings,
                    )
                }
            }
        }
      }
    }
}
