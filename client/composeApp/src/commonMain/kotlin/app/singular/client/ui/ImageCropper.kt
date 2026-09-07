package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.decodeToImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import app.singular.client.platform.PickedFile
import app.singular.client.platform.cropSquare
import kotlin.math.max
import kotlin.math.min

/**
 * Choose which square of a picture to keep, before it is uploaded.
 *
 * ## The model
 *
 * The viewport is a **fixed square** and the image moves behind it — pan to reposition, zoom to
 * choose how much fits. That is the opposite of dragging a rectangle around a static image, and
 * it is the right way round for a circular crop: the selection never changes shape or position,
 * so the preview *is* the result, and there is no way to draw a selection that isn't square.
 *
 * State is two numbers plus a scale, all in **fractions of the source image**, which is what
 * [cropSquare] takes. Keeping the maths in fractions means the editor never needs the source's
 * pixel dimensions, and a window resize doesn't move the selection.
 *
 * ## Why the crop is applied to the bytes
 *
 * A circular mask in the UI would be a lie: the whole picture would still reach the server, and
 * anyone holding the URL could see the parts that were cropped away — which people reasonably
 * assume are gone. What leaves the machine is what was kept.
 */
@Composable
fun ImageCropperDialog(
    file: PickedFile,
    title: String = "Crop picture",
    onCancel: () -> Unit,
    onConfirm: (PickedFile) -> Unit,
) {
    val bitmap = remember(file.bytes) { runCatching { file.bytes.decodeToImageBitmap() }.getOrNull() }

    // `zoom` is how many times the crop square is *smaller* than the source's short edge: 1f
    // takes the largest possible square, 3f takes a third of it. Expressed this way the slider
    // reads as magnification, which is what people expect a zoom control to mean.
    var zoom by remember(file.bytes) { mutableStateOf(1f) }
    // Centre of the crop, in fractions of the source. Starts dead centre.
    var centreX by remember(file.bytes) { mutableStateOf(0.5f) }
    var centreY by remember(file.bytes) { mutableStateOf(0.5f) }

    if (bitmap == null) {
        // An undecodable file is not worth a crop editor; say so and let them pick again.
        // DialogKeys is here for the same reason as the main editor: Escape should close this
        // too, and on desktop that only happens if we handle it ourselves.
        AlertDialog(
            onDismissRequest = onCancel,
            title = { Text("Can't read that image") },
            text = {
                DialogKeys(onDismiss = onCancel) {
                    Text("Singular couldn't open ${file.name}. Try a PNG or JPEG.")
                }
            },
            confirmButton = { TextButton(onClick = onCancel) { Text("Close") } },
        )
        return
    }

    val imageAspect = bitmap.width.toFloat() / bitmap.height.toFloat()

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title) },
        text = {
            DialogKeys(onDismiss = onCancel) {
                Column(
                    Modifier.width(cappedWidth(max = 420.dp, fractionOfWindow = 0.8f, floor = 260.dp)),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.medium)
                            .background(Color(0xFF101010))
                            .pointerInput(file.bytes) {
                                detectDragGestures { _, drag ->
                                    // A drag moves the *image*, so the crop centre moves the
                                    // other way. Divided by the viewport size to convert
                                    // pixels of gesture into fractions of the source, and by
                                    // the zoom because a magnified image travels further under
                                    // the same finger movement.
                                    val span = size.width.toFloat().coerceAtLeast(1f)
                                    val cropFraction = 1f / zoom
                                    centreX = clampCentre(
                                        centreX - (drag.x / span) * cropFraction / horizontalSpan(imageAspect),
                                        cropFraction / horizontalSpan(imageAspect),
                                    )
                                    centreY = clampCentre(
                                        centreY - (drag.y / span) * cropFraction / verticalSpan(imageAspect),
                                        cropFraction / verticalSpan(imageAspect),
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        CropCanvas(
                            bitmap = bitmap,
                            zoom = zoom,
                            centreX = centreX,
                            centreY = centreY,
                            imageAspect = imageAspect,
                        )
                    }

                    Column {
                        Text(
                            "Drag to reposition",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = zoom,
                            onValueChange = { next ->
                                zoom = next
                                // Zooming shrinks the crop, which can leave the centre outside
                                // its new legal range — re-clamp so the square never hangs off
                                // the edge of the picture.
                                val f = 1f / next
                                centreX = clampCentre(centreX, f / horizontalSpan(imageAspect))
                                centreY = clampCentre(centreY, f / verticalSpan(imageAspect))
                            },
                            valueRange = 1f..4f,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val fraction = 1f / zoom
                val spanX = fraction / horizontalSpan(imageAspect)
                val spanY = fraction / verticalSpan(imageAspect)
                val cropped = cropSquare(
                    source = file.bytes,
                    originX = (centreX - spanX / 2f).coerceIn(0f, 1f - spanX),
                    originY = (centreY - spanY / 2f).coerceIn(0f, 1f - spanY),
                    size = fraction,
                )
                // Falling back to the original rather than failing: a picture uploaded
                // uncropped is a far better outcome than a button that silently does nothing.
                onConfirm(
                    if (cropped == null) file
                    else PickedFile(
                        name = file.name.substringBeforeLast('.') + ".png",
                        contentType = "image/png",
                        bytes = cropped,
                    )
                )
            }) { Text("Use this") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/**
 * The crop square, as a fraction of the full image, along each axis.
 *
 * The crop is a fraction of the *short* edge, so on a landscape image it covers less of the
 * width than of the height — these two convert between "fraction of the short edge" and
 * "fraction of this axis".
 */
private fun horizontalSpan(aspect: Float): Float = if (aspect >= 1f) aspect else 1f
private fun verticalSpan(aspect: Float): Float = if (aspect >= 1f) 1f else 1f / aspect

/** Keeps the crop's centre far enough from the edge that the square stays inside the image. */
private fun clampCentre(value: Float, span: Float): Float {
    val half = (span / 2f).coerceAtMost(0.5f)
    return value.coerceIn(half, 1f - half)
}

/**
 * Draws the image positioned so the chosen square fills the viewport, with everything outside
 * the circle dimmed.
 *
 * The dimming is a full-viewport scrim with the circle punched out of it using `BlendMode.Clear`
 * — one draw rather than four rectangles round a hole, and it stays correct at any size. It
 * needs its own layer, which is why the canvas draws into a saved layer.
 */
@Composable
private fun CropCanvas(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    zoom: Float,
    centreX: Float,
    centreY: Float,
    imageAspect: Float,
) {
    androidx.compose.foundation.Image(
        bitmap = bitmap,
        contentDescription = "Crop preview",
        contentScale = ContentScale.None,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .drawWithContent {
                val viewport = size.minDimension
                // How large the whole image must be drawn for the chosen square to fill the
                // viewport: the crop is 1/zoom of the short edge, so the short edge is
                // viewport * zoom.
                val shortEdge = viewport * zoom
                val drawWidth = if (imageAspect >= 1f) shortEdge * imageAspect else shortEdge
                val drawHeight = if (imageAspect >= 1f) shortEdge else shortEdge / imageAspect

                // Place the image so the crop centre lands in the middle of the viewport.
                val left = viewport / 2f - centreX * drawWidth
                val top = viewport / 2f - centreY * drawHeight

                drawIntoCanvas { canvas ->
                    val paint = Paint()
                    canvas.saveLayer(
                        androidx.compose.ui.geometry.Rect(Offset.Zero, size),
                        paint,
                    )
                    drawImage(
                        image = bitmap,
                        dstOffset = androidx.compose.ui.unit.IntOffset(left.toInt(), top.toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(
                            drawWidth.toInt().coerceAtLeast(1),
                            drawHeight.toInt().coerceAtLeast(1),
                        ),
                    )
                    // Scrim everywhere, then clear the circle back out of it.
                    drawRect(Color.Black.copy(alpha = 0.55f))
                    drawCircle(
                        color = Color.Black,
                        radius = viewport / 2f,
                        center = androidx.compose.ui.geometry.Offset(viewport / 2f, viewport / 2f),
                        blendMode = BlendMode.Clear,
                    )
                    canvas.restore()
                }

                // The ring, drawn last so it sits on top of the scrim's edge.
                drawCircle(
                    color = Color.White.copy(alpha = 0.9f),
                    radius = viewport / 2f - 1f,
                    center = androidx.compose.ui.geometry.Offset(viewport / 2f, viewport / 2f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f),
                )
            },
    )
}
