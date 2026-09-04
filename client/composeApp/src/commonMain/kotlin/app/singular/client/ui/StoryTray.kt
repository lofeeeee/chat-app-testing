package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.singular.client.AppState
import app.singular.client.net.StoryDto

/**
 * The story rail. Features 5 and 20.
 *
 * A ring around the avatar carries the state, exactly as WhatsApp and Instagram do it: solid
 * accent for unseen, faded outline once watched. People already read that convention without
 * being told, and a text label would take four times the space to say less.
 */
@Composable
fun StoryTray(state: AppState) {
    var open by remember { mutableStateOf<StoryDto?>(null) }
    var composing by remember { mutableStateOf(false) }

    open?.let { story ->
        StoryViewer(
            story = story,
            isMine = story.author.id == state.currentUser?.id,
            onClose = { open = null },
        )
    }

    if (composing) {
        StoryComposer(
            onDismiss = { composing = false },
            onPost = { overlays ->
                composing = false
                // The picker opens inside postStory. Closing the dialog first keeps the modal
                // OS file dialog from fighting a Compose dialog for the window.
                state.postStory(overlays)
            },
        )
    }

    Column {
        LazyRow(
            Modifier.fillMaxWidth().height(96.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            item {
                AddStoryButton(enabled = state.uploadProgress == null) { composing = true }
            }

            items(state.stories, key = { it.id }) { story ->
                StoryBubble(
                    story = story,
                    onClick = {
                        open = story
                        if (!story.seen) state.markStorySeen(story.id)
                    },
                )
            }
        }
        androidx.compose.material3.HorizontalDivider()
    }
}

@Composable
private fun AddStoryButton(enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Add,
                contentDescription = "Add to your story",
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Your story",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StoryBubble(story: StoryDto, onClick: () -> Unit) {
    // Unseen gets a solid accent ring; watched fades to a thin outline. The ring is the whole
    // affordance â€” without it the tray is just a row of avatars with no state.
    //
    // accentSoft rather than primary: the ring is a 2.5dp stroke against the canvas, i.e. it is
    // doing a text legibility job, and `primary` is a fill colour doing a text job.
    val ringColor =
        if (story.seen) MaterialTheme.colorScheme.outline
        else LocalSingularColors.current.accentSoft
    val ringWidth = if (story.seen) 1.dp else 2.5.dp

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(64.dp).clickable(onClick = onClick),
    ) {
        Box(
            Modifier.size(52.dp).border(ringWidth, ringColor, CircleShape).padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(story.author.id, story.author.label, 44)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            story.author.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A single story, full-bleed.
 *
 * Overlays arrive as JSON and are composited by [StoryOverlayCanvas] on top of the media,
 * never baked into the uploaded image â€” so text, stickers, mentions and the music widget stay
 * editable and restylable, and a mention re-renders with someone's *current* name rather than
 * the one frozen in at posting time.
 */
@Composable
private fun StoryViewer(story: StoryDto, isMine: Boolean, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {},
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(story.author.id, story.author.label, 32)
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    // The media, then the overlays on top. Overlays are composited here rather
                    // than baked into the upload, which is what keeps a story restylable and
                    // lets a mention re-render with someone's current name.
                    val attachment = story.attachment
                    val media = attachment?.url ?: attachment?.thumbnailUrl
                    if (attachment != null && media != null) {
                        RemoteImage(
                            url = media,
                            // The attachment id, never the URL â€” presigned URLs change
                            // signature on every fetch and would miss the cache each time.
                            stableKey = attachment.id,
                            contentDescription = "Story",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                story.background ?: "Text story",
                                style = MaterialTheme.typography.titleMedium,
                            )
                        }
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
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

