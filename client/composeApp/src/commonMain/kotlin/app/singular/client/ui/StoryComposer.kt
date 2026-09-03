package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Composes a story before the image is chosen.
 *
 * Text is collected first and the picker opens on confirm, because the platform file dialog is
 * modal — opening it first and then trying to show a Compose dialog on top of the returned
 * image fights the OS on desktop and the Activity lifecycle on Android.
 *
 * What this produces is **overlay JSON**, not a flattened picture. The caption travels with the
 * story as data and is composited at view time, which is what keeps it editable and lets a
 * mention re-render with someone's current name.
 */
@Composable
fun StoryComposer(
    onDismiss: () -> Unit,
    onPost: (overlaysJson: String) -> Unit,
) {
    var caption by remember { mutableStateOf("") }
    var plate by remember { mutableStateOf(true) }
    var colorIndex by remember { mutableStateOf(0) }

    val palette = listOf("#FFFFFF", "#F23F43", "#F0B232", "#23A55A", "#3D5AFE", "#B57EDC")
    val selected = palette[colorIndex]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New story") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // A live preview of the overlay over a neutral field. Choosing text colour
                // blind against an unknown photo is guesswork; showing it isn't.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (caption.isNotBlank()) {
                        StoryOverlayCanvas(
                            overlays = listOf(previewOverlay(caption, plate, selected)),
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                "Your image goes here",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it.take(120) },
                    label = { Text("Caption") },
                    placeholder = { Text("Optional") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = plate,
                        onClick = { plate = true },
                        label = { Text("Plate") },
                    )
                    FilterChip(
                        selected = !plate,
                        onClick = { plate = false },
                        label = { Text("Plain") },
                    )
                }

                Text("Colour", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEachIndexed { index, hex ->
                        val swatch = Color(0xFF000000L.toInt() or hex.removePrefix("#").toLong(16).toInt())
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(swatch)
                                .border(
                                    width = if (index == colorIndex) 3.dp else 1.dp,
                                    color = if (index == colorIndex) MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                                .clickable { colorIndex = index }
                        )
                    }
                }

                Text(
                    "Disappears after 24 hours.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val overlays =
                    if (caption.isBlank()) emptyList()
                    else listOf(previewOverlay(caption, plate, selected))
                onPost(encodeOverlays(overlays))
            }) { Text("Choose image & post") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Where the caption sits.
 *
 * Fractional coordinates, so the same story reads identically on a phone and a desktop. Placed
 * low-centre because that is where captions sit in every story app, and because it keeps clear
 * of the author header at the top of the viewer.
 */
private fun previewOverlay(text: String, plate: Boolean, color: String) = StoryOverlay(
    type = "text",
    x = 0.14f,
    y = 0.70f,
    value = text,
    style = if (plate) "plate" else "plain",
    color = color,
)
