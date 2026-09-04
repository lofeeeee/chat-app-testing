package app.singular.client.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.singular.client.AppState
import app.singular.client.net.ChannelDto
import app.singular.client.net.UserDto

@Composable
fun ChatScreen(state: AppState, onOpenSessions: () -> Unit, onOpenSettings: () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        ChannelSidebar(state, onOpenSessions, onOpenSettings, Modifier.width(268.dp).fillMaxHeight())
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (state.selectedChannel == null) EmptyState() else Conversation(state)
        }
    }
}

// ---------------------------------------------------------------------------
// Presence
// ---------------------------------------------------------------------------

/**
 * Label only — the colour comes from [Presence.statusColor], defined once in Palette.kt with
 * the reasoning for why it isn't theme-driven.
 */
private fun statusLabel(status: String): String = when (status) {
    "ONLINE" -> "Online"
    "AWAY" -> "Away"
    "DND" -> "Do not disturb"
    "INVISIBLE" -> "Invisible"
    else -> "Offline"
}

/**
 * An avatar with a presence dot notched into its corner.
 *
 * The dot sits on a ring of the sidebar colour rather than flush against the avatar, so it
 * stays legible over a busy image — the difference between a readable indicator and a smudge.
 * The ring reads `notch` from the extended palette rather than `surface`: this composable is
 * used on the canvas and on cards too, and the notch has to be opaque in whichever of those
 * it lands on, which is a property `surface` doesn't guarantee.
 */
@Composable
private fun AvatarWithStatus(user: UserDto, status: String, size: Int) {
    Box(Modifier.size(size.dp)) {
        Avatar(user.id, user.label, size)
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 2.dp, y = 2.dp)
                .size((size / 3).coerceAtLeast(10).dp)
                .clip(CircleShape)
                .background(LocalSingularColors.current.notch)
                .padding(2.dp)
                .clip(CircleShape)
                .background(Presence.statusColor(status))
        )
    }
}

// ---------------------------------------------------------------------------
// Sidebar
// ---------------------------------------------------------------------------

@Composable
private fun ChannelSidebar(
    state: AppState,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var handle by remember { mutableStateOf("") }
    var statusMenu by remember { mutableStateOf(false) }

    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            state.currentUser?.let { me ->
                Box {
                    Box(Modifier.clickable { statusMenu = true }) {
                        AvatarWithStatus(me, state.myStatus, 36)
                    }
                    DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                        listOf("ONLINE", "AWAY", "DND", "INVISIBLE").forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier.size(9.dp).clip(CircleShape)
                                                .background(Presence.statusColor(option))
                                        )
                                        Spacer(Modifier.width(9.dp))
                                        Text(statusLabel(option))
                                    }
                                },
                                onClick = { state.setStatus(option); statusMenu = false },
                            )
                        }
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        me.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // The handle belongs HERE — it's how people find and add you. It has no
                    // business appearing above every line of a conversation.
                    Text(
                        me.handle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onOpenSessions) {
                Icon(Icons.Filled.Devices, contentDescription = "Devices and sign-ins")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
        HorizontalDivider()

        LazyColumn(Modifier.weight(1f)) {
            items(state.channels, key = { it.id }) { channel ->
                ChannelRow(
                    channel = channel,
                    selfId = state.currentUser?.id,
                    status = channel.members.firstOrNull { it.id != state.currentUser?.id }
                        ?.let(state::statusOf) ?: "OFFLINE",
                    muted = state.mutedChannels[channel.id] == true,
                    selected = channel.id == state.selectedChannel?.id,
                    onClick = { state.openChannel(channel) },
                )
            }
        }

        HorizontalDivider()
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = handle,
                onValueChange = { handle = it },
                label = { Text("Add by handle") },
                placeholder = { Text("orbit#2989") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = {
                    state.openDmWithHandle(handle.trim()); handle = ""
                }),
            )
            TextButton(
                onClick = { state.openDmWithHandle(handle.trim()); handle = "" },
                enabled = handle.contains('#') && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open conversation") }
        }
    }
}

@Composable
private fun ChannelRow(
    channel: ChannelDto,
    selfId: String?,
    status: String,
    muted: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val other = channel.members.firstOrNull { it.id != selfId }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (other != null) AvatarWithStatus(other, status, 34)
        else Avatar(channel.id, channel.name ?: "#", 34)

        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                channel.title(selfId),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                if (other != null) statusLabel(status) else "Group",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (muted) {
            Icon(
                Icons.Filled.NotificationsOff,
                contentDescription = "Muted",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Conversation
// ---------------------------------------------------------------------------

@Composable
private fun Conversation(state: AppState) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val channel = state.selectedChannel ?: return
    val other = channel.members.firstOrNull { it.id != state.currentUser?.id }

    LaunchedEffect(state.messages.size, channel.id) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            other?.let { AvatarWithStatus(it, state.statusOf(it), 32) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    channel.title(state.currentUser?.id),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                other?.let {
                    Text(
                        statusLabel(state.statusOf(it)),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val muted = state.mutedChannels[channel.id] == true
            IconButton(onClick = { state.toggleMute(channel.id) }) {
                Icon(
                    if (muted) Icons.Filled.NotificationsOff else Icons.Filled.Notifications,
                    contentDescription = if (muted) "Unmute" else "Mute",
                    tint = if (muted) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            other?.let { person ->
                TextButton(onClick = {
                    if (person.blockedByViewer) state.unblockUser(person.id)
                    else state.blockUser(person.id)
                }) {
                    Text(if (person.blockedByViewer) "Unblock" else "Block")
                }
            }
        }
        HorizontalDivider()

        StoryTray(state)

        MessageList(
            messages = state.messages,
            selfId = state.currentUser?.id,
            layout = if (state.chatLayout == "COMPACT") ChatLayout.COMPACT else ChatLayout.BUBBLES,
            listState = listState,
            modifier = Modifier.weight(1f),
        )

        state.uploadProgress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }

        TypingIndicator(state.typingUsers.values.toList())

        state.error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }

        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = {
                    draft = it
                    // Throttled inside AppState — one mutation per 3s, not per keystroke.
                    if (it.isNotBlank()) state.onTyping()
                },
                placeholder = { Text("Message  ·  Shift+Enter for a new line") },
                modifier = Modifier
                    .weight(1f)
                    // onPreviewKeyEvent, not onKeyEvent: the field must never see the Enter that
                    // sends, or it inserts a newline first and leaves a blank line behind.
                    .onPreviewKeyEvent { event ->
                        val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                        when {
                            event.type != KeyEventType.KeyDown -> false
                            !isEnter -> false
                            // Shift+Enter falls through so the field inserts the newline itself.
                            event.isShiftPressed -> false
                            else -> {
                                if (draft.isNotBlank()) { state.send(draft); draft = "" }
                                true
                            }
                        }
                    },
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (draft.isNotBlank()) { state.send(draft); draft = "" }
                }),
            )
            IconButton(
                onClick = { state.attachAndSend(draft); draft = "" },
                enabled = state.uploadProgress == null,
            ) {
                Icon(Icons.Filled.AttachFile, contentDescription = "Attach a file")
            }
            IconButton(
                onClick = { state.send(draft); draft = "" },
                enabled = draft.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

/**
 * "Orbit is typing" with three pulsing dots.
 *
 * Holds its row height whether or not anyone is typing. Letting it appear and vanish would
 * shove the whole message list up and down every few seconds, which is far more distracting
 * than the indicator is useful.
 */
@Composable
private fun TypingIndicator(users: List<UserDto>) {
    val label = when (users.size) {
        0 -> ""
        1 -> "${users[0].label} is typing"
        2 -> "${users[0].label} and ${users[1].label} are typing"
        else -> "${users.size} people are typing"
    }

    Row(
        Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (users.isEmpty()) return@Row

        val transition = rememberInfiniteTransition(label = "typing")
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, delayMillis = index * 160),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot$index",
            )
            Box(
                Modifier
                    .padding(end = 3.dp)
                    .size(5.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha))
            )
        }
        Spacer(Modifier.width(7.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No conversation open", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.padding(4.dp))
            Text(
                "Enter someone's handle on the left to start one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
