package app.singular.client.ui

import androidx.compose.runtime.Composable

/** Desktop has no system back button or gesture — Escape is the equivalent, and the shell's
 *  keyboard layer already routes it through the same dispatcher. */
@Composable
actual fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit) = Unit
