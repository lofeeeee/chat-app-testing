package app.singular.client

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import app.singular.client.net.SingularClient
import app.singular.client.ui.buildImageLoader
import app.singular.client.ui.ChatScreen
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import app.singular.client.ui.LoginScreen
import app.singular.client.ui.SessionsScreen
import app.singular.client.ui.SettingsScreen
import app.singular.client.ui.SingularTheme
import app.singular.client.ui.Presets

@Composable
fun App(
    httpUrl: String = SingularClient.DEFAULT_HTTP,
    wsUrl: String = SingularClient.DEFAULT_WS,
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
        Surface(Modifier.fillMaxSize()) {
            when {
                !state.signedIn -> LoginScreen(state, qr)
                showSettings -> SettingsScreen(state) { showSettings = false }
                showSessions -> SessionsScreen(sessions) { showSessions = false }
                else -> ChatScreen(
                    state,
                    onOpenSessions = { showSessions = true },
                    onOpenSettings = { showSettings = true },
                )
            }
        }
    }
}
