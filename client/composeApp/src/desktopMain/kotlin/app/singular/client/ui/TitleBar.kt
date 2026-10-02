package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.FilterNone
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.awt.Frame
import java.awt.MouseInfo
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.WindowStateListener

/** Compose colour → AWT colour, for the handful of places that talk to real OS windows. */
private fun androidx.compose.ui.graphics.Color.toAwtColor(): java.awt.Color = java.awt.Color(
    (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)

/**
 * The window's own title bar, drawn by the app.
 *
 * The OS bar is a fixed slab of system chrome: it doesn't follow the app's theme, it's a
 * different grey in light mode and dark, and on Windows it's the one part of the window still
 * looking like 2012 while everything below it doesn't. Drawing it ourselves makes the whole
 * window one surface.
 *
 * The cost is that *everything* the OS bar did has to be re-implemented, and the pieces are
 * easy to miss. All of them are here: dragging, double-click to maximise, edge snapping,
 * hover states, and — the one that actually bit — keeping the maximise button in step with a
 * window state the OS can change behind our back.
 *
 * **Close goes red on hover; the other two don't.** That isn't decoration — it's the one
 * affordance separating "put this away" from "lose what I was doing", and every desktop
 * platform has converged on it.
 */
@Composable
fun SingularTitleBar(
    window: ComposeWindow,
    onClose: () -> Unit,
    title: String = "Singular",
) {
    val controller = remember(window) { WindowController(window) }

    // The snap ghost, tinted with the live accent so a theme change mid-session recolours it.
    // DisposableEffect rather than plain remember: an AWT window is a real OS resource, and
    // leaking one on every recomposition of a long-lived title bar would pile up invisible
    // always-on-top windows. hide() is idempotent, so dispose-on-leave is safe even if the
    // drag never opened it.
    val accent = MaterialTheme.colorScheme.primary.toAwtColor()
    val snapPreview = remember(accent) { SnapPreviewWindow(accent) }
    DisposableEffect(snapPreview) {
        onDispose { snapPreview.hide() }
    }

    // Mirrors the window's real geometry rather than tracking our own idea of it.
    //
    // This is the alt-tab bug. Minimise, restore, snap and Win+Up can all be driven by the OS,
    // and a flag the app writes when *its* button is clicked knows nothing about any of that —
    // after one alt-tab the button offered "restore" for a window that wasn't maximised.
    //
    // Both listeners are needed. `WindowStateListener` hears iconify/deiconify and native
    // maximise; `ComponentListener` hears the resizes that our own bounds-based maximise
    // produces, which change no window *state* at all and so fire nothing else.
    var maximised by remember(window) { mutableStateOf(controller.isMaximised) }
    DisposableEffect(window) {
        val onState = WindowStateListener { maximised = controller.isMaximised }
        val onGeometry = object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) { maximised = controller.isMaximised }
            override fun componentMoved(e: ComponentEvent?) { maximised = controller.isMaximised }
        }
        window.addWindowStateListener(onState)
        window.addComponentListener(onGeometry)
        onDispose {
            window.removeWindowStateListener(onState)
            window.removeComponentListener(onGeometry)
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .titleBarGestures(window, controller, snapPreview),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(14.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.weight(1f))

        CaptionButton(Icons.Filled.Remove, "Minimise") {
            window.extendedState = window.extendedState or Frame.ICONIFIED
        }
        CaptionButton(
            // The glyph names the state you're in, not the one you'd move to: two overlapping
            // squares is the universal mark for an already-maximised window.
            icon = if (maximised) Icons.Filled.FilterNone else Icons.Filled.CropSquare,
            description = if (maximised) "Restore" else "Maximise",
            onClick = { controller.toggleMaximised(); maximised = controller.isMaximised },
        )
        CaptionButton(Icons.Filled.Close, "Close", danger = true, onClick = onClose)
    }
}

/**
 * Dragging, double-click, and snap-on-release — with a live preview of where the snap would land.
 *
 * Compose Desktop ships `WindowDraggableArea`, and it is not enough: it moves the window and
 * nothing else, so there is no snapping and no way to drag a maximised window loose. Both are
 * things people do without thinking about them, so both are implemented here.
 *
 * Dragging a maximised window **restores it under the cursor** — grabbing the bar of a
 * maximised window and pulling down is how you un-maximise, and a window that either refused
 * to move or leapt away from the pointer would be worse than not supporting it.
 *
 * While the pointer is inside a snap zone, a translucent accent-tinted ghost shows the target
 * rectangle; released there, the window snaps to exactly what the ghost showed.
 */
private fun Modifier.titleBarGestures(
    window: ComposeWindow,
    controller: WindowController,
    snapPreview: SnapPreviewWindow,
): Modifier = this
    .pointerInput(window) {
        detectTapGestures(onDoubleTap = { controller.toggleMaximised() })
    }
    .pointerInput(window) {
        // Screen coordinates throughout. The window is moving underneath the gesture, so any
        // offset measured relative to the window itself would feed back into its own motion.
        var grabOffset = Point(0, 0)

        detectDragGestures(
            onDragStart = {
                val pointer = MouseInfo.getPointerInfo()?.location ?: Point(0, 0)
                grabOffset =
                    if (controller.isMaximised) controller.restoreForDrag(pointer)
                    else Point(pointer.x - window.x, pointer.y - window.y)
            },
            onDrag = { _, _ ->
                val pointer = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                window.setLocation(pointer.x - grabOffset.x, pointer.y - grabOffset.y)
                // The ghost follows the zone *during* the drag — snap is decided on release,
                // and the preview is the promise of what that release will do.
                snapPreview.show(snapTarget(snapZoneFor(pointer), pointer))
            },
            onDragEnd = {
                // Snap is decided on release, from where the pointer ended up — and lands on
                // exactly the rectangle the ghost was showing, because both read snapTarget().
                snapPreview.hide()
                val pointer = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures
                val zone = snapZoneFor(pointer)
                if (zone == SnapZone.NONE) controller.noteMoved() else controller.applySnap(zone, pointer)
            },
            onDragCancel = {
                // A cancelled drag (input consumed elsewhere, focus stolen) must not leave a
                // ghost pinned on screen claiming a snap that will never happen.
                snapPreview.hide()
            },
        )
    }

/**
 * One caption button, at the 46×36 the platform uses.
 *
 * Deliberately not an `IconButton`: those are round and carry Material's 48dp minimum touch
 * target, which would leave gaps in a strip that has to read as one continuous bar all the way
 * into the corner of the window.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CaptionButton(
    icon: ImageVector,
    description: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    var hovered by remember { mutableStateOf(false) }

    val background = when {
        !hovered -> Color.Transparent
        danger -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    }
    val tint =
        if (hovered && danger) MaterialTheme.colorScheme.onError
        else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        Modifier
            .size(width = 46.dp, height = 36.dp)
            .background(background)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}
