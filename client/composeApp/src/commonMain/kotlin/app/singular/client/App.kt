package app.singular.client

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import app.singular.client.net.SingularClient
import app.singular.client.ui.BackDispatcher
import app.singular.client.ui.LocalBackDispatcher
import app.singular.client.ui.LocalReducedMotion
import app.singular.client.ui.Route
import app.singular.client.ui.SystemBackHandler
import app.singular.client.ui.buildImageLoader
import app.singular.client.ui.ChatScreen
import app.singular.client.ui.KeyboardScope
import app.singular.client.ui.ProvideWindowSize
import app.singular.client.ui.ShortcutsDialog
import app.singular.client.ui.StoryEditor
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
import app.singular.client.ui.VoiceNotePlayer

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

    // Voice-note playback borrows the signed-in client: one HTTP stack, one set of
    // credentials, and one place that can be closed on the way out.
    remember(client) { VoiceNotePlayer.bind(client) }


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

    // -- Navigation ----------------------------------------------------------
    //
    // A small back stack of routes with Chat as the floor. Only the top is ever drawn, so
    // screens can never stack invisibly — the rule the old one-flag-per-screen model enforced
    // by hand. Escape and the Android system back pop one level at a time.

    /** The destinations above the chat. Only the last entry is drawn. */
    val backStack = remember { mutableStateListOf<Route>(Route.Chat) }

    /** The shortcuts sheet is a dialog, not a destination — it overlays any screen. */
    var showShortcuts by remember { mutableStateOf(false) }

    // -1 = going deeper (new screen slides in from the right), +1 = going back (the old one
    // slides out to the right). Set before the stack mutates so the transition reads the
    // direction of travel rather than inferring it from stale state.
    var navDirection by remember { mutableStateOf(-1) }

    /**
     * Navigate to [route]. A route already on the stack moves to the top rather than
     * duplicating — pressing Ctrl+, from inside Settings would otherwise bury it under
     * another Settings.
     */
    fun go(route: Route) {
        navDirection = if (backStack.size > 1 && backStack.last() == route) 1 else -1
        backStack.remove(route)
        backStack.add(route)
    }

    /** The Ctrl-chords toggle: pressing the one for the screen you're on closes it. */
    fun toggle(route: Route) {
        if (backStack.size > 1 && backStack.last() == route) {
            navDirection = 1
            backStack.removeAt(backStack.lastIndex)
        } else {
            go(route)
        }
    }

    /**
     * Pop one level of the route stack, directly — no overlay dispatch.
     *
     * This is what a screen's own back arrow and Done button call. [goBack] is what Escape
     * calls, and the difference matters: the story editor registers a discard-guard
     * interceptor, so its "Discard" button must not route through the dispatcher again or it
     * would re-open the guard it just answered.
     */
    fun popRoute(): Boolean {
        if (backStack.size <= 1) return false
        navDirection = 1
        backStack.removeAt(backStack.lastIndex)
        return true
    }

    /** Server settings is opened for *this* selected server, straight at the section asked for. */
    fun openServerSettings(section: ServerSettingsSection) {
        val guild = state.selectedGuild ?: return
        go(Route.ServerSettings(guild.id, section))
    }

    val backDispatcher = remember { BackDispatcher() }

    /**
     * What "back" means, in one place.
     *
     * The order is the order things were stacked: a registered overlay interceptor (reaction
     * sheet, story editor) first, then the shortcuts sheet, then one level of the route stack.
     * Every screen calls this rather than flipping its own state, so Escape, a back arrow, the
     * Android back button and a Done button can never disagree about where they land.
     *
     * Returns false when there is nothing left to go back to, which is what lets the caller
     * pass the key on instead of swallowing it.
     */
    val goBack: () -> Boolean = {
        when {
            backDispatcher.dispatch() -> true
            showShortcuts -> { showShortcuts = false; true }
            backStack.size > 1 -> {
                navDirection = 1
                backStack.removeAt(backStack.lastIndex)
                true
            }
            else -> false   // ChatScreen handles its own layer: closing the conversation
        }
    }

    // Restoring the stored session. Starts true so the very first frame is the spinner, not
    // the login form — the check is a network round trip and would otherwise flash.
    var restoring by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        // The result is ignored on purpose: success flips `signedIn`, failure leaves it false,
        // and either way this screen is done waiting.
        runCatching { state.tryRestoreSession() }
        restoring = false
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
      CompositionLocalProvider(
          LocalBackDispatcher provides backDispatcher,
          LocalReducedMotion provides state.reduceMotion,
      ) {
       Column(Modifier.fillMaxSize()) {
        titleBar()
        Surface(Modifier.weight(1f).fillMaxWidth()) {
         // Measured once, here, and published to every screen. Wrapping inside the Surface
         // rather than around the whole window means the title bar's height is already
         // subtracted, so panels size against the room they can actually use.
         ProvideWindowSize {
            // One attempt to pick up the stored session, before anything is drawn. Holding a
            // spinner for it rather than showing the login form first is the difference
            // between "the app remembered me" and a form that flashes up and vanishes.
            if (restoring) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@ProvideWindowSize
            }

            if (!state.signedIn) {
                // No shell shortcuts before sign-in: there is nowhere to navigate to, and a
                // stray Ctrl+D on a login form should do nothing rather than something.
                LoginScreen(state, qr)
                return@ProvideWindowSize
            }

            if (showShortcuts) ShortcutsDialog { showShortcuts = false }

            // The Android system back runs exactly the same chain Escape does. Enabled only
            // when there's something to consume — otherwise the system default (leaving the
            // app) applies.
            SystemBackHandler(
                enabled = backStack.size > 1 || showShortcuts,
                onBack = { goBack() },
            )

            // The shell's own key layer, wrapping every signed-in screen. Escape unwinds one
            // level; the Ctrl chords jump between destinations from wherever you are.
            KeyboardScope(
                // Which screen is showing. Changing it re-takes keyboard focus, so Escape
                // keeps working after navigating to a screen that has nothing focusable on it.
                refocusKey = backStack.last(),
                onPreviewKey = { event ->
                    when {
                        event.isPress && event.key == Key.Escape -> goBack()
                        handleGlobalShortcut(
                            event,
                            onSettings = { toggle(Route.Settings) },
                            onSessions = { toggle(Route.Sessions) },
                            onStories = { toggle(Route.Stories) },
                            onMentions = { toggle(Route.Mentions) },
                            onHelp = { showShortcuts = !showShortcuts },
                        ) -> true
                        else -> false
                    }
                }
            ) {
                // Only the top of the stack is drawn — screens can't stack invisibly. The
                // transition is a short slide-and-fade: going deeper slides the new screen in
                // from the right; going back reverses it. Reduced motion snaps instead.
                // (The flag is read here, outside the spec — a transitionSpec lambda is not a
                // composable context, so it can only capture, not read composition locals.)
                val reducedMotion = LocalReducedMotion.current
                AnimatedContent(
                    targetState = backStack.last(),
                    transitionSpec = {
                        if (reducedMotion) {
                            fadeIn(snap()) togetherWith fadeOut(snap())
                        } else if (navDirection < 0) {
                            (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { it / 14 }) togetherWith
                                (fadeOut(tween(140)))
                        } else {
                            (fadeIn(tween(180))) togetherWith
                                (fadeOut(tween(140)) + slideOutHorizontally(tween(220)) { it / 14 })
                        }
                    },
                    label = "route",
                ) { route ->
                    when (route) {
                        Route.Chat -> ChatScreen(
                            state,
                            onOpenSessions = { go(Route.Sessions) },
                            onOpenSettings = { go(Route.Settings) },
                            onOpenStories = { go(Route.Stories) },
                            onOpenMentions = { go(Route.Mentions) },
                            onOpenServerSettings = ::openServerSettings,
                        )

                        Route.Settings -> SettingsScreen(state, onClose = { popRoute() })
                        Route.Stories -> StoriesScreen(
                            state,
                            onCompose = { go(Route.StoryEditor) },
                            onClose = { popRoute() },
                        )
                        Route.Mentions -> MentionsScreen(state, onClose = { popRoute() })
                        Route.Sessions -> SessionsScreen(sessions, onClose = { popRoute() })
                        Route.StoryEditor -> StoryEditor(state, onClose = { popRoute() })

                        is Route.ServerSettings -> {
                            // Resolved per composition: `loadGuilds()` replaces the DTOs, so
                            // holding one would show stale data after the first save. A guild
                            // that vanished from the list — left, kicked — drops the stale
                            // route and lands on the chat, which is where you are.
                            val guild = state.guilds.firstOrNull { it.id == route.guildId }
                            if (guild != null) {
                                ServerSettingsScreen(
                                    state = state,
                                    guild = guild,
                                    initialSection = route.section,
                                    onClose = { popRoute() },
                                )
                            } else {
                                LaunchedEffect(route) { backStack.remove(route) }
                            }
                        }
                    }
                }
            }
         }
        }
       }
      }
    }
}
