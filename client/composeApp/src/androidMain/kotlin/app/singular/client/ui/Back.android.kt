package app.singular.client.ui

import androidx.compose.runtime.Composable

/** The Android system back gesture/button, fed into the same chain Escape uses on desktop. */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) {
    androidx.activity.compose.BackHandler(enabled = enabled, onBack = onBack)
}
