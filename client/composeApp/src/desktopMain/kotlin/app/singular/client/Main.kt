package app.singular.client

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.DpSize
import app.singular.client.platform.DataDir
import app.singular.client.ui.SingularTitleBar
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.io.File
import java.util.UUID

fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1180.dp, 780.dp))

    Window(
        onCloseRequest = ::exitApplication,
        title = if (DataDir.isEphemeral) "Singular (temporary)" else "Singular",
        state = windowState,
        // The OS bar is gone so the app can draw its own, themed one. Everything that bar did
        // — dragging, double-click to maximise, the caption buttons — now lives in
        // SingularTitleBar, which is the whole cost of doing this.
        undecorated = true,
        // Kept resizable: undecorated windows lose the OS resize grips, and Compose Desktop
        // supplies its own only while this is true.
        resizable = true,
    ) {
        // A floor for the layout.
        //
        // Undecorated windows keep Compose's resize grips, and without a minimum those will
        // happily drag the window down to a few pixels — at which point no amount of
        // responsive layout helps, because there is genuinely nowhere to put anything. 520x400
        // is the smallest size the compact layout still reads at: rail, one pane, composer.
        LaunchedEffect(window) { window.minimumSize = Dimension(520, 400) }

        // Point at another host with -Dsingular.server=https://chat.example.com
        val host = System.getProperty("singular.server")

        // The bar sits above the app rather than inside it, so every screen — login included —
        // gets it without having to remember to draw one.
        App(
            httpUrl = host?.let { "$it/graphql" } ?: SingularDefaults.HTTP,
            wsUrl = host?.let { "${it.replaceFirst("http", "ws")}/graphql" }
                ?: SingularDefaults.WS,
            // Passed in rather than wrapped around App, so the bar is drawn inside the theme
            // and follows the user's palette. The WindowScope receiver is captured from here.
            titleBar = {
                SingularTitleBar(
                    window = window,
                    onClose = ::exitApplication,
                    title = if (DataDir.isEphemeral) "Singular — temporary window" else "Singular",
                )
            },
        )
    }
}

private object SingularDefaults {
    const val HTTP = "http://localhost:8080/graphql"
    const val WS = "ws://localhost:8080/graphql"
}

actual fun deviceId(): String = DeviceIdStore.current()

/**
 * Desktop install id.
 *
 * A file under the user's config directory, not a MAC address — see the note on the expect
 * declaration. Phase 5 moves this into DPAPI on Windows and the Keychain on macOS so it can't
 * be copied between machines to impersonate a device.
 */
private object DeviceIdStore {
    // Through DataDir, so a throwaway window gets its own install id rather than sharing the
    // real one. Sharing would make both windows a single entry in "where you're signed in",
    // and revoking either would sign out both.
    private val file: File by lazy { DataDir.file("device-id") }

    fun current(): String {
        if (file.exists()) {
            file.readText().trim().takeIf { it.isNotEmpty() }?.let { return it }
        }
        val fresh = UUID.randomUUID().toString()
        runCatching { file.writeText(fresh) }
        return fresh
    }
}

/**
 * Reported to the server and shown on the approving device's confirmation screen, so "Windows
 * desktop, from 203.0.113.9" is what a user is asked to trust — not a user-agent string.
 */
actual val platformName: String = buildString {
    val os = System.getProperty("os.name") ?: "Unknown"
    append(
        when {
            os.startsWith("Windows") -> "Windows"
            os.startsWith("Mac") -> "macOS"
            else -> os
        }
    )
    append(" desktop")
}
