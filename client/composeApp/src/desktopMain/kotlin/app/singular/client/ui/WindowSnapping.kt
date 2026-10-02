package app.singular.client.ui

import androidx.compose.ui.awt.ComposeWindow
import java.awt.Color
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
enum class SnapZone {
    NONE,
    MAXIMISE,
    LEFT,
    RIGHT,
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
}

/**
 * Classifies a pointer position on screen into a snap zone.
 *
 * Screen coordinates, not window coordinates: the gesture is "I threw this window at the edge
 * of the display", and where the window's own top-left happens to be says nothing about that.
 *
 * Supports Windows Aero Snap:
 *  - Drag to top edge -> Maximise
 *  - Drag to left / right edge -> Half screen
 *  - Drag to any of the 4 corners -> Quadrant (quarter screen / square)
 */
fun snapZoneFor(pointer: Point): SnapZone {
    val area = workArea(pointer)
    val edge = 10
    val corner = 80

    val atLeft = pointer.x <= area.x + edge
    val atRight = pointer.x >= area.x + area.width - edge - 1
    val atTop = pointer.y <= area.y + edge
    val atBottom = pointer.y >= area.y + area.height - edge - 1

    val nearLeft = pointer.x <= area.x + corner
    val nearRight = pointer.x >= area.x + area.width - corner - 1
    val nearTop = pointer.y <= area.y + corner
    val nearBottom = pointer.y >= area.y + area.height - corner - 1

    return when {
        // Corners: top-left, top-right, bottom-left, bottom-right
        (atLeft && nearTop) || (atTop && nearLeft) -> SnapZone.TOP_LEFT
        (atRight && nearTop) || (atTop && nearRight) -> SnapZone.TOP_RIGHT
        (atLeft && nearBottom) || (atBottom && nearLeft) -> SnapZone.BOTTOM_LEFT
        (atRight && nearBottom) || (atBottom && nearRight) -> SnapZone.BOTTOM_RIGHT

        // Edges: top (maximise), left (half), right (half)
        atTop -> SnapZone.MAXIMISE
        atLeft -> SnapZone.LEFT
        atRight -> SnapZone.RIGHT
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
        val halfW = area.width / 2
        val halfH = area.height / 2
        if (window.extendedState != Frame.NORMAL) window.extendedState = Frame.NORMAL
        when (zone) {
            SnapZone.NONE -> Unit
            SnapZone.MAXIMISE -> {
                // Not `maximise()`: that would save the mid-drag position as the restore
                // rectangle. Whatever was saved when the drag began is the one worth going
                // back to.
                window.bounds = area
            }
            SnapZone.LEFT -> {
                window.setBounds(area.x, area.y, halfW, area.height)
            }
            SnapZone.RIGHT -> {
                window.setBounds(area.x + halfW, area.y, halfW, area.height)
            }
            SnapZone.TOP_LEFT -> {
                window.setBounds(area.x, area.y, halfW, halfH)
            }
            SnapZone.TOP_RIGHT -> {
                window.setBounds(area.x + halfW, area.y, halfW, halfH)
            }
            SnapZone.BOTTOM_LEFT -> {
                window.setBounds(area.x, area.y + halfH, halfW, halfH)
            }
            SnapZone.BOTTOM_RIGHT -> {
                window.setBounds(area.x + halfW, area.y + halfH, halfW, halfH)
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

/**
 * The rectangle a snap would land the window in, for previewing during a drag.
 *
 * `null` for [SnapZone.NONE] — the preview is "no preview", not a full-work-area rectangle.
 * This is `applySnap`'s geometry without the side effects, so the ghost and the real snap can
 * never disagree: a preview that showed one rectangle and snapped to another would be worse
 * than no preview at all.
 */
fun snapTarget(zone: SnapZone, pointer: Point): Rectangle? {
    val area = workArea(pointer)
    val halfW = area.width / 2
    val halfH = area.height / 2
    return when (zone) {
        SnapZone.NONE -> null
        SnapZone.MAXIMISE -> area
        SnapZone.LEFT -> Rectangle(area.x, area.y, halfW, area.height)
        SnapZone.RIGHT -> Rectangle(area.x + halfW, area.y, halfW, area.height)
        SnapZone.TOP_LEFT -> Rectangle(area.x, area.y, halfW, halfH)
        SnapZone.TOP_RIGHT -> Rectangle(area.x + halfW, area.y, halfW, halfH)
        SnapZone.BOTTOM_LEFT -> Rectangle(area.x, area.y + halfH, halfW, halfH)
        SnapZone.BOTTOM_RIGHT -> Rectangle(area.x + halfW, area.y + halfH, halfW, halfH)
    }
}

/**
 * The translucent ghost rectangle shown while a drag hovers a snap zone.
 *
 * ## Why a second window rather than drawing inside our own
 *
 * The snap target can be *larger* than the window being dragged — a half-screen snap of a
 * small window, or a maximise of anything. A preview drawn inside the window's own Compose
 * tree can only ever cover the window itself, so it would preview only part of the rectangle
 * and clip at the window edge. A borderless, translucent AWT window can sit anywhere on
 * screen and be any size, which is the whole point.
 *
 * ## Why it doesn't break the drag
 *
 * It looks like it should steal the release event — the pointer is over the ghost when the
 * button comes up. It doesn't, because AWT performs an implicit grab: once mousePressed is
 * delivered, every drag and the release go to the *same* component, regardless of what window
 * the pointer happens to be over by then. The gesture in the title bar keeps reading the
 * global pointer location and receives its own release. The ghost is also `focusable = false`
 * and registers no listeners, so it can't take focus or clicks in its own right.
 *
 * ## Appearance
 *
 * The window's background carries the alpha — an undecorated window with a translucent
 * background is the standard AWT overlay trick, and needs no painting code at all. Deliberately
 * faint (~24%): the user is mid-gesture and reading the *geometry*, not the styling. It follows
 * the app's accent rather than a hardcoded blue so it reads as part of the product on every theme.
 */
class SnapPreviewWindow(accent: Color) {

    private val tint = Color(accent.red, accent.green, accent.blue, 60)

    private var window: java.awt.Window? = null

    /** Shows (or moves) the ghost at [target]. Null hides it. */
    fun show(target: Rectangle?) {
        if (target == null) { hide(); return }

        val existing = window
        if (existing == null) {
            val ghost = object : java.awt.Window(null as java.awt.Frame?) {
                // Hand-rolled no-op painting keeps some L&Fs from filling the background
                // opaquely on first show; the translucent background does the real work.
                override fun paint(g: java.awt.Graphics) {}
            }
            // A bare java.awt.Window carries no decorations by construction — there is no
            // setUndecorated to call, unlike Frame.
            ghost.background = tint
            ghost.isAlwaysOnTop = true
            ghost.isFocusable = false
            ghost.bounds = target
            ghost.isVisible = true
            window = ghost
        } else {
            existing.bounds = target
        }
    }

    /** Tears the ghost down. Cheap; called on every drag end. */
    fun hide() {
        window?.dispose()
        window = null
    }
}
