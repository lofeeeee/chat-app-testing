package app.singular.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.singular.client.net.AttachmentDto
import kotlin.math.roundToInt

/**
 * The fullscreen image viewer.
 *
 * ## Why it exists
 *
 * A chat client without an image viewer makes people do the worst thing: download the file
 * just to read it. Every other interaction here was already built (reactions, voice notes,
 * stories) — images were the one thing you could see but not touch.
 *
 * ## The transition
 *
 * A fade + 6% scale from the thumbnail's centre. A true shared-element morph (`sharedElement`)
 * would need a `SharedTransitionLayout` around the whole app, and that lookahead pass is paid
 * by every node in the subtree — including the scrolling message list. A scale-in gives the
 * same "one surface expanding" read at a fraction of the cost, and degrades to a plain fade
 * under reduced motion.
 *
 * ## Zoom model
 *
 * Pinch to scale, drag to pan, double-tap to reset — one [detectTransformGestures], so a
 * two-finger pinch that also moves doesn't fight a separate drag. Dismiss on Escape (back
 * dispatcher), a scrim click, or a downward drag past the threshold.
 */
object ImageViewerState {
    var attachment by mutableStateOf<AttachmentDto?>(null)
        private set

    fun open(target: AttachmentDto) {
        attachment = target
    }

    fun close() {
        attachment = null
    }
}

/** Renders the viewer when an attachment is open. Call once, near the app root. */
@Composable
fun ImageViewerHost() {
    val attachment = ImageViewerState.attachment

    AnimatedVisibility(
        visible = attachment != null,
        enter = fadeIn(tween(180)) +
            (if (LocalReducedMotion.current) fadeIn(tween(0)) else scaleIn(initialScale = 0.92f, animationSpec = tween(200))),
        exit = fadeOut(tween(120)),
    ) {
        attachment?.let { Viewer(it) }
    }
}

@Composable
private fun Viewer(attachment: AttachmentDto) {
    val source = attachment.url ?: attachment.thumbnailUrl

    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current

    BackHandler { ImageViewerState.close(); true }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }

        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.86f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { ImageViewerState.close() },
                )
                .pointerInput(Unit) {
                    detectTransformGestures(panZoomLock = false) { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.5f, 8f)
                        offset = if (scale == 1f) {
                            Offset(
                                x = (offset.x + pan.x).coerceIn(-containerW / 2, containerW / 2),
                                y = (offset.y + pan.y).coerceIn(-containerH / 2, containerH / 2),
                            )
                        } else {
                            Offset(
                                x = offset.x + pan.x * scale,
                                y = offset.y + pan.y * scale,
                            )
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = { scale = 1f; offset = Offset.Zero })
                },
            contentAlignment = Alignment.Center,
        ) {
            if (source != null) {
                RemoteImage(
                    url = source,
                    stableKey = attachment.id,
                    contentDescription = attachment.filename,
                    modifier = Modifier
                        .fillMaxSize()
                        .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    contentScale = ContentScale.Fit,
                )
            } else {
                Text(
                    "Couldn't load image",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.TopEnd) {
                IconButton(onClick = { ImageViewerState.close() }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Close",
                        tint = Color.White.copy(alpha = 0.85f),
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().padding(16.dp),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Text(
                    attachment.filename,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f),
                )
            }
        }
    }
}
