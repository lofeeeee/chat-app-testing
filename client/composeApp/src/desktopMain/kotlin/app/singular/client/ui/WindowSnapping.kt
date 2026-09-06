package app.singular.client.ui

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit

/**
 * The window-management behaviour an undecorated window loses, put back by hand.
 *
 * Removing the OS title bar removes more than a strip of pixels: Windows implements maximise,
 * Aero Snap and the double-click gesture through hit-testing on the *non-client area*, and an
 * undecorated window has none.
 *
 * ## Why this doesn't use `Frame.MAXIMIZED_BOTH`
 *
 * The obvious implementation — `setMaximizedBounds(workArea)` then `extendedState =
 * MAXIMIZED_BOTH` — does produce the right rectangle when tested in isolation. It is still the
 * wrong tool here, for two reasons found the hard way:
 *
 *  * **Compose owns `WindowState.placement`** and syncs it onto the frame. Writing
 *    `extendedState` behind its back means two things drive one property, and whichever runs
 *    last wins — so the window can silently revert.
 *  * **Coordinate spaces differ.** After a native maximise on a scaled display, `getBounds()`
 *    reports *device* pixels (1920×1020 at 125%) while `GraphicsConfiguration.getBounds()` is
 *    user space (1536×816). Any code comparing the two to ask "am I maximised?" is comparing
 *    numbers from different systems.
 *
 * Setting the bounds directly avoids both. It stays entirely in user space, it round-trips
 * exactly, and `placement` never leaves `Floating` so Compose has nothing to disagree with.
 * Maximise is then simply "the window is the size of the work area" — which is what maximise
 * has always meant, and is precisely *not* fullscreen: the taskbar keeps its strip.
 */

/** The screen area excluding the taskbar and any other reserved edges, in user space. */
fun workArea(at: Point? = null): Rectangle {
    val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
    // The device under the pointer, so snapping on a second monitor snaps to *that* screen.
    val device = at
        ?.let { p -> env.screenDevices.firstOrNull { it.defaultConfiguration.bounds.contains(p) } }
        ?: env.defaultScreenDevice

    val config = device.defaultConfiguration
    val bounds = config.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom,
    )
}

/** Where a drag ended, and therefore what the window should become. */
enum class SnapZone { NONE, MAXIMISE, LEFT, RIGHT }

/**
 * Classifies a pointer position on screen into a snap zone.
 *
 * Screen coordinates, not window coordinates: the gesture is "I threw this window at the edge
 * of the display", and where the window's own top-left happens to be says nothing about that.
 *
 * The thresholds are deliberately small. A generous edge is worse than none — every drag that
 * merely passes near the top would snap, and undoing a snap costs more than making one.
 */
fun snapZoneFor(pointer: Point): SnapZone {
    val area = workArea(pointer)
    val edge = 8

    return when {
        pointer.y <= area.y + edge -> SnapZone.MAXIMISE
        pointer.x <= area.x + edge -> SnapZone.LEFT
        pointer.x >= area.x + area.width - edge - 1 -> SnapZone.RIGHT
        else -> SnapZone.NONE
    }
}

/**
 * Owns the window's maximise/restore/snap state.
 *
 * A class rather than free functions because restoring needs to remember where the window was
 * before it was maximised, and that has to live somewhere. Holding it here — rather than in a
 * composable — means a recomposition can't lose it.
 */
class WindowController(private val window: ComposeWindow) {

    private var restoreBounds: Rectangle? = null

    /**
     * Maximised means "filling the work area".
     *
     * Derived from geometry rather than a flag, so it stays true however the window got that
     * way: our button, a drag to the top edge, or Windows itself via Win+Up. A flag would need
     * every one of those paths to remember to update it, and the one that forgot would be the
     * alt-tab desync all over again.
     *
     * The native state is still consulted, because Win+Up really does set it.
     */
    val isMaximised: Boolean
        get() {
            if ((window.extendedState and Frame.MAXIMIZED_BOTH) == Frame.MAXIMIZED_BOTH) return true
            val area = workArea(centreOfWindow())
            val b = window.bounds
            // A pixel of tolerance: a window placed by us matches exactly, but one restored by
            // the OS can land a hair off and should still read as maximised.
            return kotlin.math.abs(b.width - area.width) <= 2 &&
                kotlin.math.abs(b.height - area.height) <= 2 &&
                kotlin.math.abs(b.x - area.x) <= 2 &&
                kotlin.math.abs(b.y - area.y) <= 2
        }

    fun maximise() {
        if (isMaximised) return
        restoreBounds = window.bounds
        // Any native maximise has to be cleared first, or setBounds is ignored.
        if (window.extendedState != Frame.NORMAL) window.extendedState = Frame.NORMAL
        window.bounds = workArea(centreOfWindow())
    }

    fun restore() {
        if (window.extendedState != Frame.NORMAL) window.extendedState = Frame.NORMAL
        // A window that started maximised has nothing saved; two thirds of the work area is a
        // reasonable "smaller than this" rather than leaving it stuck.
        val target = restoreBounds ?: workArea(centreOfWindow()).let { area ->
            Rectangle(
                area.x + area.width / 6,
                area.y + area.height / 6,
                area.width * 2 / 3,
                area.height * 2 / 3,
            )
        }
        window.bounds = target
        restoreBounds = null
    }

    fun toggleMaximised() {
        if (isMaximised) restore() else maximise()
    }

    /**
     * Called when a drag begins on a maximised window: restores it so it can be moved, and
     * reports where the pointer should sit along the restored title bar.
     *
     * Grabbing a maximised window near its right edge and pulling down should leave the window
     * under the cursor, not jump it left — so the grab keeps its *proportional* place.
     */
    fun restoreForDrag(pointer: Point): Point {
        val before = window.bounds
        val fraction = (pointer.x - before.x).toDouble() / before.width.coerceAtLeast(1)
        restore()
        return Point((window.width * fraction).toInt(), (pointer.y - before.y).coerceAtMost(36))
    }

    fun applySnap(zone: SnapZone, pointer: Point) {
        val area = workArea(pointer)
        when (zone) {
            SnapZone.NONE -> Unit
            SnapZone.MAXIMISE -> {
                // Not `maximise()`: that would save the mid-drag position as the restore
                // rectangle. Whatever was saved when the drag began is the one worth going
                // back to.
                if (window.extendedState != Frame.NORMAL) window.extendedState = Frame.NORMAL
                window.bounds = area
            }
            SnapZone.LEFT -> {
                if (window.extendedState != Frame.NORMAL) window.extendedState = Frame.NORMAL
                window.setBounds(area.x, area.y, area.width / 2, area.height)
            }
            SnapZone.RIGHT -> {
                if (window.extendedState != Frame.NORMAL) window.extendedState = Frame.NORMAL
                window.setBounds(area.x + area.width / 2, area.y, area.width / 2, area.height)
            }
        }
    }

    /** Remembers where a plain drag left the window, so a later maximise can come back to it. */
    fun noteMoved() {
        if (!isMaximised) restoreBounds = null
    }

    private fun centreOfWindow() =
        Point(window.x + window.width / 2, window.y + window.height / 2)
}
