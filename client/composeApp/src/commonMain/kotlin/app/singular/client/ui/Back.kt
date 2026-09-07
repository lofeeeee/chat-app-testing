package app.singular.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * The back layer beneath the route stack.
 *
 * Escape and the Android system back both walk the same chain, in this order:
 *
 *   1. The most recently registered overlay interceptor ([BackHandler]) — the reaction sheet,
 *      the mention autocomplete, the story editor's discard guard.
 *   2. The route stack in `App` — pops one screen at a time.
 *   3. The open conversation (`ChatScreen` closes the channel itself).
 *
 * Interceptors exist because of how preview key events flow: an ancestor's `onPreviewKeyEvent`
 * sees a key *before* any descendant, so a nested overlay can never preempt the shell's own
 * Escape branch. Registering instead of intercepting inverts that: the shell asks the stack
 * first, and the innermost layer gets first refusal without fighting the event order.
 */
class BackDispatcher {
    private val handlers = mutableListOf<() -> Boolean>()

    fun register(handler: () -> Boolean): () -> Unit {
        handlers.add(handler)
        return { handlers.remove(handler) }
    }

    /** Last-registered handler wins; returns false when nothing wants the back request. */
    fun dispatch(): Boolean = handlers.toList().asReversed().any { it() }
}

/** Provided by `App`. Screens register sub-layer interceptors through [BackHandler]. */
val LocalBackDispatcher = compositionLocalOf<BackDispatcher?> { null }

/**
 * Register [onBack] while [enabled]. The last-enabled registration is consulted first, which
 * matches "the thing opened most recently closes first".
 *
 * Returning true from [onBack] consumes the back request; returning false lets the next layer
 * down handle it.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Boolean) {
    val dispatcher = LocalBackDispatcher.current ?: return
    val current by rememberUpdatedState(onBack)
    DisposableEffect(dispatcher, enabled) {
        if (!enabled) {
            onDispose { }
        } else {
            val unregister = dispatcher.register { current() }
            onDispose { unregister() }
        }
    }
}

/**
 * The platform's system back — the Android gesture/button. Wired to the same dispatcher and
 * route stack as Escape, so the two can never disagree about what "back" means. The desktop
 * actual is a no-op: Escape already covers it.
 */
@Composable
expect fun SystemBackHandler(enabled: Boolean, onBack: () -> Unit)
