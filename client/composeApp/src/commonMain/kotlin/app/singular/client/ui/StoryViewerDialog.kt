package app.singular.client.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.singular.client.net.StoryDto

/**
 * A single story.
 *
 * Media first, overlays composited on top by [StoryOverlayCanvas] — never baked into the
 * uploaded image. That is what keeps text, stickers, mentions and the music widget editable
 * and restylable, and why a mention re-renders with someone's *current* name rather than the
 * one frozen in at posting time.
 */
@Composable
fun StoryViewerDialog(story: StoryDto, isMine: Boolean, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {},
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(story.author, 32)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(story.author.label, fontWeight = FontWeight.SemiBold)
                    Text(
                        shortTime(story.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = "Close")
                }
            }
        },
        text = {
          // Escape closes the story and lands back on the list, rather than falling through
          // and closing the Stories screen underneath it.
          DialogKeys(onDismiss = onClose) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val attachment = story.attachment
                val media = attachment?.url ?: attachment?.thumbnailUrl

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(420.dp)
                        .clip(MaterialTheme.shapes.medium)
                        // A text story's ground is its authored background; a photo story's is
                        // near-black, so letterboxing round an image doesn't glow.
                        .background(
                            if (media == null) StoryBackgrounds.byId(story.background).brush
                            else SolidColor(Color(0xFF101010))
                        ),
                ) {
                    if (attachment != null && media != null) {
                        RemoteImage(
                            url = media,
                            // The attachment id, never the URL — presigned URLs get a fresh
                            // signature on every fetch and would miss the cache every time.
                            stableKey = attachment.id,
                            contentDescription = "Story",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }

                    StoryOverlayCanvas(
                        overlays = parseOverlays(story.overlays),
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                // Only the author sees who watched. Showing it to viewers would turn a story
                // into a record of who else was looking.
                if (isMine) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Visibility,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${story.viewCount} viewer" + if (story.viewCount == 1) "" else "s",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Text(
                    "Disappears " + shortTime(story.expiresAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
          }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
