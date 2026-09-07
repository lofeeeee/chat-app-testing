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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.singular.client.AppState
import app.singular.client.net.StoryDto

/**
 * Stories, as its own screen — WhatsApp's Status tab rather than a rail above the messages.
 *
 * It lives here and not in the conversation because a story has nothing to do with the chat you
 * happen to have open. Sitting above the message list it implied the two were related, cost
 * ~96dp of every conversation, and reloaded on every channel switch.
 *
 * Split into **My status** and **Recent updates** for the same reason every status app does:
 * posting and watching are different intentions, and one row of yours at the top is the fastest
 * possible answer to "did mine go up?".
 */
@Composable
fun StoriesScreen(state: AppState, onCompose: () -> Unit, onClose: () -> Unit) {
    var open by remember { mutableStateOf<StoryDto?>(null) }

    val me = state.currentUser?.id
    val mine = state.stories.filter { it.author.id == me }
    val others = state.stories.filter { it.author.id != me }

    // Refreshed on entry rather than polled. A story lasts 24 hours; nothing here changes fast
    // enough to be worth a subscription.
    LaunchedEffect(Unit) { state.loadStories() }

    open?.let { story ->
        StoryViewerDialog(
            story = story,
            isMine = story.author.id == me,
            onClose = { open = null },
        )
    }

    // The composer is a route of its own (pushed by [onCompose]) rather than a local mode:
    // Escape then unwinds editor → stories list → chat one level at a time, instead of
    // closing the whole screen from inside the editor.
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A back arrow, not "Done". Nothing here is being submitted — this is a place you
            // navigated to, and "Done" reads like it will save something.
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Stories",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }

        state.uploadProgress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }

        HorizontalDivider()

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            item {
                SectionLabel("My status")
                MyStatusRow(
                    latest = mine.firstOrNull(),
                    busy = state.uploadProgress != null,
                    onAdd = onCompose,
                    onOpen = { mine.firstOrNull()?.let { open = it } },
                )
            }

            if (others.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(6.dp))
                    SectionLabel("Recent updates")
                }
                items(others, key = { it.id }) { story ->
                    StoryListRow(story) {
                        open = story
                        if (!story.seen) state.markStorySeen(story.id)
                    }
                }
            } else {
                item {
                    Spacer(Modifier.height(28.dp))
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "No recent updates",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Stories from people you talk to show up here for 24 hours.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 6.dp),
    )
}

/**
 * Your own row. Doubles as the post button.
 *
 * One row that changes meaning rather than two controls: when you have nothing up it reads
 * "Add to my story", and once you do it becomes the thing you tap to check who watched.
 */
@Composable
private fun MyStatusRow(
    latest: StoryDto?,
    busy: Boolean,
    onAdd: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) { if (latest == null) onAdd() else onOpen() }
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.BottomEnd) {
            if (latest != null) {
                Box(
                    Modifier
                        .size(56.dp)
                        .border(2.5.dp, LocalSingularColors.current.accentSoft, CircleShape)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(latest.author, 48)
                }
            } else {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (latest == null) "Add to my story" else "My story",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                when {
                    busy -> "Uploading…"
                    latest == null -> "Share a photo that disappears after 24 hours"
                    else -> "${latest.viewCount} viewer" + (if (latest.viewCount == 1) "" else "s") +
                        " · " + shortTime(latest.createdAt)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (latest != null) {
            TextButton(onClick = onAdd, enabled = !busy) { Text("Add") }
        }
    }
}

@Composable
private fun StoryListRow(story: StoryDto, onClick: () -> Unit) {
    // Unseen carries a solid accent ring; watched fades to a thin outline. The ring is the
    // whole affordance — people read it without a label.
    val ringColor =
        if (story.seen) MaterialTheme.colorScheme.outline
        else LocalSingularColors.current.accentSoft
    val ringWidth = if (story.seen) 1.dp else 2.5.dp

    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(56.dp).border(ringWidth, ringColor, CircleShape).padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Avatar(story.author, 48)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                story.author.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (story.seen) FontWeight.Normal else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                shortTime(story.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
