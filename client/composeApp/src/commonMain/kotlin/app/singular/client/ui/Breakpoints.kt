package app.singular.client.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How much room the app has, and what the layout should do about it.
 *
 * ## Why this exists
 *
 * Every panel in the app was a fixed `dp`: a 72dp rail, a 260dp sidebar, a 240dp settings nav,
 * a 320dp story inspector. Those numbers are fine in a 1180dp window and nonsense in a 700dp
 * one — the rail and sidebar alone take 332dp, so a narrow window ends up with a conversation
 * squeezed into a column too thin to read, and the story editor tries to fit a 9:16 preview
 * beside a 320dp panel in 280dp of space.
 *
 * Note this is about **window size, not screen resolution or DPI**. Compose already handles
 * DPI: a `dp` is the same physical size at 100% and 200% scaling, which is why the app looks
 * right on a HiDPI display without doing anything. What it cannot do is guess that a panel
 * which makes sense at 1400dp should be narrower — or gone — at 600dp. That judgement is here.
 *
 * ## Thresholds
 *
 * Chosen from what the layout actually needs rather than from a table:
 *
 *  * [COMPACT] — under 760dp. Chrome plus a readable conversation doesn't fit side by side, so
 *    the sidebar and the conversation take turns.
 *  * [MEDIUM] — 760 to 1100dp. Both fit, but the chrome has to give up some width.
 *  * [EXPANDED] — 1100dp and up. Everything at its designed size.
 */
enum class WindowWidth { COMPACT, MEDIUM, EXPANDED;

    val isCompact: Boolean get() = this == COMPACT
    val atLeastMedium: Boolean get() = this != COMPACT
}

/**
 * `static` because it changes only when the window is resized.
 *
 * A regular `compositionLocalOf` tracks reads and invalidates only what read it — the right
 * choice for something that changes often. This one changes on a resize and is read by nearly
 * every panel, so the bookkeeping costs more than the targeted invalidation saves.
 */
val LocalWindowWidth = staticCompositionLocalOf { WindowWidth.EXPANDED }

/** The measured width, for the places that want a proportion rather than a bucket. */
val LocalWindowWidthDp = staticCompositionLocalOf { 1180.dp }

/**
 * Measures the space available and publishes it to everything inside.
 *
 * Wrapped once, at the top of the app. Passing a size down through every composable that needs
 * it would mean adding a parameter to a dozen signatures whose callers have no opinion on it.
 */
@Composable
fun ProvideWindowSize(content: @Composable () -> Unit) {
    // fillMaxSize so children asking for the full area still get it — a wrap-content box here
    // would hand every screen its own content size back, which is circular.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val bucket = when {
            width < 760.dp -> WindowWidth.COMPACT
            width < 1100.dp -> WindowWidth.MEDIUM
            else -> WindowWidth.EXPANDED
        }
        CompositionLocalProvider(
            LocalWindowWidth provides bucket,
            LocalWindowWidthDp provides width,
            content = content,
        )
    }
}

/**
 * The width of a fixed side panel at the current size.
 *
 * Three authored values rather than a formula. A panel that scales continuously with the
 * window spends its life at sizes nobody designed — a 233dp sidebar is not better than a 220dp
 * one, it is just unreviewed — and text inside it reflows on every drag of the window edge.
 */
@Composable
fun panelWidth(expanded: Dp, medium: Dp = expanded * 0.85f, compact: Dp = expanded * 0.72f): Dp =
    when (LocalWindowWidth.current) {
        WindowWidth.EXPANDED -> expanded
        WindowWidth.MEDIUM -> medium
        WindowWidth.COMPACT -> compact
    }

/**
 * A maximum that also respects the window.
 *
 * For things sized by their content up to a cap — a message bubble, a settings card, the emoji
 * panel. The cap is what the design wants; the fraction is what the window can actually spare.
 * Taking the smaller of the two is what stops a 520dp bubble in a 400dp column.
 */
@Composable
fun cappedWidth(max: Dp, fractionOfWindow: Float = 0.7f, floor: Dp = 160.dp): Dp {
    val available = LocalWindowWidthDp.current * fractionOfWindow
    return minOf(max, available).coerceAtLeast(floor)
}
