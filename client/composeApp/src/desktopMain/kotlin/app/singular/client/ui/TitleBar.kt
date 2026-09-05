package app.singular.client.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.onClick
import androidx.compose.foundation.window.WindowDraggableArea
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState

/**
 * The window's own title bar, drawn by the app.
 *
 * The OS bar is a fixed slab of system chrome: it doesn't follow the app's theme, it's a
 * different grey in light mode and dark, and on Windows it's the one part of the window still
 * looking like 2012 while everything below it doesn't. Drawing it ourselves makes the whole
 * window one surface.
 *
 * The cost is that everything a title bar does has to be re-implemented, and the pieces are
 * easy to forget: dragging, double-click to maximise, and hover states that make the buttons
 * feel like buttons. All three are here.
 *
 * **Close goes red on hover; the other two don't.** That isn't decoration — it's the one
 * affordance separating "put this away" from "lose what I was doing", and every desktop
 * platform has converged on it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WindowScope.SingularTitleBar(
    state: WindowState,
    onClose: () -> Unit,
    title: String = "Singular",
) {
    // WindowDraggableArea is what makes this behave like a title bar rather than a painted
    // strip: without it the window can't be moved at all, since the OS bar that used to do it
    // is gone. Double-click to maximise has to be re-added for the same reason.
    WindowDraggableArea(
        Modifier.onClick(onDoubleClick = { toggleMaximised(state) }) { }
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
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

            CaptionButton(Icons.Filled.Remove, "Minimise") { state.isMinimized = true }
            CaptionButton(
                // The glyph names the state you're in, not the one you'd move to: two
                // overlapping squares is the universal mark for an already-maximised window.
                icon = if (state.placement == WindowPlacement.Maximized) Icons.Filled.FilterNone
                       else Icons.Filled.CropSquare,
                description = if (state.placement == WindowPlacement.Maximized) "Restore"
                              else "Maximise",
                onClick = { toggleMaximised(state) },
            )
            CaptionButton(Icons.Filled.Close, "Close", danger = true, onClick = onClose)
        }
    }
}

private fun toggleMaximised(state: WindowState) {
    state.placement =
        if (state.placement == WindowPlacement.Maximized) WindowPlacement.Floating
        else WindowPlacement.Maximized
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
