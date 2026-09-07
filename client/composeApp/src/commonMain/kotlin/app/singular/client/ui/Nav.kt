package app.singular.client.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The app's destinations above the chat.
 *
 * `App` keeps these on a small back stack with [Chat] as the floor. Only the top of the stack
 * is ever drawn, so screens can never stack invisibly — the rule the old flag-per-screen model
 * enforced by hand. Escape (and the Android system back) pops one level at a time, which is
 * what lets a sub-screen like the story editor return to its list instead of throwing you all
 * the way back to the chat.
 */
sealed interface Route {
    data object Chat : Route
    data object Settings : Route
    data object Stories : Route
    data object Mentions : Route
    data object Sessions : Route

    /** The full-screen story composer. Pushed above [Stories] so back returns to the list. */
    data object StoryEditor : Route

    /**
     * Server settings carries its target so the screen can resolve the guild live rather than
     * capture a snapshot — `loadGuilds()` replaces the DTOs after every save, and a captured
     * one would freeze the screen on pre-save data.
     */
    data class ServerSettings(val guildId: String, val section: ServerSettingsSection) : Route
}

/**
 * When true, transitions and entrance animations are skipped.
 *
 * A device-level preference rather than a synced account setting: motion sensitivity is a
 * property of the person at this machine.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * A one-shot settle-in for surfaces that appear instantly (dialogs, popup panels): a short
 * fade with a 6% zoom, so the surface reads as placed rather than stamped.
 *
 * Deliberately entrance-only — dismissal should feel instant, so there is no matching exit.
 * A no-op under [LocalReducedMotion].
 */
@Composable
fun Modifier.animateEntrance(): Modifier {
    if (LocalReducedMotion.current) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, animationSpec = tween(160, easing = FastOutSlowInEasing))
    }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        val scale = 0.94f + 0.06f * p
        scaleX = scale
        scaleY = scale
    }
}
