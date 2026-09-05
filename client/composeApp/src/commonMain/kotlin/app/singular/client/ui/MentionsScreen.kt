package app.singular.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.singular.client.AppState
import app.singular.client.net.MessageDto

/**
 * The mentions inbox — everything aimed at you, newest first.
 *
 * A message can land here via a direct `<@you>`, a role you hold, or an @everyone/@here —
 * the server resolves all three at query time, so this screen is one query, not three.
 * Visibility is checked server-side too: a mention in a channel you've since lost access
 * to is simply absent rather than erroring the whole list.
 *
 * Refreshed on entry. A "new mention" badge belongs to the unread-state work; pulling the
 * list here is the read side of it.
 */
@Composable
fun MentionsScreen(state: AppState, onClose: () -> Unit) {
    LaunchedEffect(Unit) { state.loadMentionInbox() }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "Mentions",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
        }

        HorizontalDivider()

        if (state.mentionInbox.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("Nobody's tagged you yet.", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Messages that mention you — @you, a role you hold, or @everyone — collect here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            val resolver = remember(state.mentionInbox, state.currentUser?.id) {
                MentionResolver(
                    usersById = state.mentionInbox.associate { it.author.id to it.author },
                    rolesById = emptyMap(),
                    channelsById = emptyMap(),
                    selfId = state.currentUser?.id,
                )
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(state.mentionInbox, key = { it.id }) { message ->
                    MentionRow(message, resolver)
                    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun MentionRow(message: MessageDto, resolver: MentionResolver) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.Top,
    ) {
        Avatar(message.author.id, message.author.label, size = 32)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    message.author.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    shortTime(message.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!message.content.isNullOrBlank()) {
                Text(
                    messageAnnotated(message.content, MaterialTheme.colorScheme.onSurface, resolver),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
