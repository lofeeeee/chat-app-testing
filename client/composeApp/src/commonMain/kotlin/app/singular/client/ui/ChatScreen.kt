package app.singular.client.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.AmpStories
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.singular.client.AppState
import app.singular.client.net.ChannelDto
import app.singular.client.net.GuildDto
import app.singular.client.net.UserDto

@Composable
fun ChatScreen(
    state: AppState,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStories: () -> Unit,
    onOpenMentions: () -> Unit,
    onOpenServerSettings: (ServerSettingsSection) -> Unit,
) {
    // Focus targets the shortcuts hand control to. Held here rather than inside the two
    // composables that own the fields, because Ctrl+E is pressed from anywhere on the screen —
    // including from inside the other field.
    val composerFocus = remember { FocusRequester() }
    val handleFocus = remember { FocusRequester() }

    // Held here, not in the sidebar, because Ctrl+F has to be able to switch to the Friends
    // tab before asking for its field. Focusing a field on a hidden tab silently does nothing,
    // which reads as a broken shortcut.
    var tab by remember { mutableStateOf(HomeTab.CHATS) }
    var pendingHandleFocus by remember { mutableStateOf(false) }

    // Its own KeyboardScope, not just a modifier on the Row.
    //
    // Preview events travel from the root of the *focused path* downwards. App's shell scope
    // holds focus until something in here takes it, and a bare modifier on this Row would sit
    // below the focused node rather than on the path to it — so Alt+Down would work only once
    // a conversation was already open, which is exactly when you don't need it.
    //
    // App's scope is still an ancestor, so it gets first refusal: Escape closes a screen if
    // one is open, and only falls through to here — closing the conversation — when none is.
    KeyboardScope(
        onPreviewKey = { event ->
            when {
                event.isPress && event.key == Key.Escape && state.selectedChannel != null -> {
                    state.closeChannel(); true
                }
                handleNavigationShortcut(
                    event,
                    onChannelStep = { state.stepChannel(it) },
                    onGuildStep = { state.stepGuild(it) },
                    onGuildIndex = { state.openGuildAt(it) },
                    onFocusComposer = { runCatching { composerFocus.requestFocus() } },
                    onFocusSearch = {
                        state.openGuild(null)
                        tab = HomeTab.FRIENDS
                        // The field is composed by the tab switch above, which lands in the
                        // next frame — so the request has to wait for it.
                        pendingHandleFocus = true
                    },
                ) -> true
                else -> false
            }
        }
    ) {
      Row(Modifier.fillMaxSize()) {
        // Requested a frame late, once the Friends tab has actually placed the field.
        if (pendingHandleFocus) {
            LaunchedEffect(Unit) {
                runCatching { handleFocus.requestFocus() }
                pendingHandleFocus = false
            }
        }

        ServerRail(state, Modifier.width(72.dp))
        ChannelSidebar(
            state, onOpenSessions, onOpenSettings, onOpenStories, onOpenMentions,
            onOpenServerSettings, handleFocus,
            tab, onTabChange = { tab = it },
            Modifier.width(260.dp).fillMaxHeight(),
        )
        VerticalDivider()
        Box(Modifier.weight(1f).fillMaxHeight()) {
            if (state.selectedChannel == null) EmptyState(state)
            else Conversation(state, composerFocus)
        }
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

/**
 * The middle column: what you can open, and who you are.
 *
 * Laid out the way Discord's is, and for the reason Discord's is: the list is the thing you
 * use constantly and the account controls are the thing you touch once a week, so the list
 * gets the top — where the eye lands — and the account sits in a fixed bar at the bottom where
 * it never moves and never competes for the space a long list needs.
 *
 * The previous arrangement put avatar, name, handle and three icon buttons on one 248dp row,
 * which is what squeezed "Nova" into "Nov / a".
 */
@Composable
private fun ChannelSidebar(
    state: AppState,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenStories: () -> Unit,
    onOpenMentions: () -> Unit,
    onOpenServerSettings: (ServerSettingsSection) -> Unit,
    handleFocus: FocusRequester,
    tab: HomeTab,
    onTabChange: (HomeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.background(MaterialTheme.colorScheme.surface)) {
        Box(Modifier.weight(1f)) {
            // One or the other, never both: `selectedGuild` is the single variable deciding
            // whether this column is a server's channel list or your own conversations.
            val guild = state.selectedGuild
            if (guild != null) GuildChannelList(state, guild, onOpenServerSettings)
            else DirectMessageHome(state, tab, handleFocus, onTabChange)
        }

        HorizontalDivider()
        ProfileBar(state, onOpenStories, onOpenSessions, onOpenSettings, onOpenMentions)
    }
}

private enum class HomeTab { CHATS, FRIENDS }

// ---------------------------------------------------------------------------
// Direct messages: Chats | Friends
// ---------------------------------------------------------------------------

@Composable
private fun DirectMessageHome(
    state: AppState,
    tab: HomeTab,
    handleFocus: FocusRequester,
    onTabChange: (HomeTab) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab.ordinal) {
            Tab(
                selected = tab == HomeTab.CHATS,
                onClick = { onTabChange(HomeTab.CHATS) },
                text = { Text("Chats") },
            )
            Tab(
                selected = tab == HomeTab.FRIENDS,
                onClick = { onTabChange(HomeTab.FRIENDS) },
                text = { Text("Friends") },
            )
        }

        when (tab) {
            HomeTab.CHATS -> ConversationList(state)
            HomeTab.FRIENDS -> FriendsTab(state, handleFocus)
        }
    }
}

@Composable
private fun ConversationList(state: AppState) {
    if (state.channels.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                "No conversations yet. Open the Friends tab to start one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(state.channels, key = { it.id }) { channel ->
            DirectMessageRow(
                channel = channel,
                selfId = state.currentUser?.id,
                status = channel.members.firstOrNull { it.id != state.currentUser?.id }
                    ?.let(state::statusOf) ?: "OFFLINE",
                preview = state.lastMessages[channel.id]?.preview(state.currentUser?.id),
                muted = state.mutedChannels[channel.id] == true,
                unread = state.unread[channel.id] == true,
                selected = channel.id == state.selectedChannel?.id,
                onClick = { state.openChannel(channel) },
            )
        }
    }
}

/**
 * Adding people, off the front page.
 *
 * A handle box is something you use when you meet someone new, not something you look at all
 * day, so it stopped being the permanent bottom third of the sidebar. Nothing about it
 * changed except where it lives.
 */
@Composable
private fun FriendsTab(state: AppState, handleFocus: FocusRequester) {
    var handle by remember { mutableStateOf("") }
    val selfId = state.currentUser?.id
    val focus = LocalFocusManager.current

    // Everyone you already have a 1:1 conversation with. That is the whole friend list this
    // app has — there is no accept/decline request flow yet, and inventing an empty one here
    // would be a screen that never has anything in it.
    val friends = state.channels
        .filter { it.type == "DM" }
        .mapNotNull { channel -> channel.members.firstOrNull { it.id != selfId } }
        .distinctBy { it.id }

    val open = { state.openDmWithHandle(handle.trim()); handle = "" }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedTextField(
                value = handle,
                onValueChange = { handle = it },
                label = { Text("Add by handle") },
                placeholder = { Text("orbit#2989") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(handleFocus)
                    .formField(focus, enabled = handle.contains('#'), onConfirm = open),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { open() }),
            )
            Text(
                "Their handle is on their profile — press Enter to open the conversation.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider()
        SidebarSectionLabel("Friends — ${friends.size}")

        LazyColumn(Modifier.weight(1f)) {
            items(friends, key = { it.id }) { person ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            state.channels
                                .firstOrNull { c -> c.members.any { it.id == person.id } }
                                ?.let(state::openChannel)
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AvatarWithStatus(person, state.statusOf(person), 32)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            person.label,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // The handle belongs here and nowhere else — it is how people find
                        // each other, not something to repeat above every line of a chat.
                        Text(
                            person.handle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A conversation row.
 *
 * The second line is the last message, not a presence label. Presence is already on the
 * avatar's dot, so spending the only other line in the row on a word for the same fact told
 * you nothing; what you actually want to know at a glance is what was said and by whom.
 */
@Composable
private fun DirectMessageRow(
    channel: ChannelDto,
    selfId: String?,
    status: String,
    preview: String?,
    muted: Boolean,
    unread: Boolean,
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
                fontWeight = if (selected || unread) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Empty rather than a placeholder: a brand-new conversation has nothing to
                // preview, and "No messages yet" is noise on every row you just created.
                preview.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                color = if (unread) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (unread) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(4.dp))
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
// Server channels
// ---------------------------------------------------------------------------

/**
 * A server's channels, grouped under collapsible categories.
 *
 * Channels are `# name` in a single line of text, not avatar rows. A channel is a place, not a
 * person — giving it a round portrait made a server's channel list read as a list of people,
 * which is exactly the confusion the `#` convention exists to prevent.
 *
 * Channels with no category fall into one group so the list is never a bare stack of rows with
 * no heading; real categories (`GUILD_CATEGORY` channels) are used when a server has them.
 */
@Composable
private fun GuildChannelList(
    state: AppState,
    guild: GuildDto,
    onOpenServerSettings: (ServerSettingsSection) -> Unit,
) {
    // Collapsed set, keyed by category id. Local to the composable: which groups you folded
    // is a glance-level preference, not something worth a round trip to store.
    val collapsed = remember(guild.id) { mutableStateListOf<String>() }

    // Read here, in the composable, and passed down as a plain set. Reading the state list
    // only from inside the LazyColumn's content lambda would leave whether a fold redraws the
    // list up to the item-provider's snapshot observation; reading it at this level makes the
    // whole list rebuild on a fold, which is unambiguous.
    val hidden: Set<String> = collapsed.toSet()

    val categories = guild.channels.filter { it.isCategory }.sortedBy { it.name }
    val text = guild.channels.filter { it.type == "GUILD_TEXT" }
    val grouped = text.groupBy { it.parentId }

    Column(Modifier.fillMaxSize()) {
        GuildHeader(state, guild, onOpenServerSettings)
        HorizontalDivider()

        LazyColumn(Modifier.weight(1f)) {
            // Uncategorised first — in a server with no categories at all, this is the
            // entire list, and burying it under named groups would be strange.
            grouped[null]?.takeIf { it.isNotEmpty() }?.let { loose ->
                channelGroup(
                    id = UNCATEGORISED,
                    label = "Chats",
                    channels = loose,
                    state = state,
                    isCollapsed = UNCATEGORISED in hidden,
                    onToggle = { toggle(collapsed, UNCATEGORISED) },
                )
            }

            categories.forEach { category ->
                channelGroup(
                    id = category.id,
                    label = category.name ?: "Channels",
                    channels = grouped[category.id].orEmpty(),
                    state = state,
                    isCollapsed = category.id in hidden,
                    onToggle = { toggle(collapsed, category.id) },
                )
            }
        }
    }
}

private const val UNCATEGORISED = "~loose"

private fun toggle(collapsed: MutableList<String>, id: String) {
    if (!collapsed.remove(id)) collapsed.add(id)
}

private fun LazyListScope.channelGroup(
    id: String,
    label: String,
    channels: List<ChannelDto>,
    state: AppState,
    isCollapsed: Boolean,
    onToggle: () -> Unit,
) {
    item(key = "cat-$id") {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The chevron rotates rather than swapping glyphs, so the control reads as the
            // same object in both states.
            val turn by animateFloatAsState(
                targetValue = if (isCollapsed) -90f else 0f,
                label = "category-chevron",
            )
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (isCollapsed) "Expand $label" else "Collapse $label",
                modifier = Modifier.size(16.dp).rotate(turn),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(2.dp))
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (isCollapsed) return

    items(channels, key = { it.id }) { channel ->
        GuildChannelRow(
            channel = channel,
            selected = channel.id == state.selectedChannel?.id,
            unread = state.unread[channel.id] == true,
            muted = state.mutedChannels[channel.id] == true,
            onClick = { state.openChannel(channel) },
        )
    }
}

@Composable
private fun GuildChannelRow(
    channel: ChannelDto,
    selected: Boolean,
    unread: Boolean,
    muted: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The hash is drawn as text at the same size as the name, so it reads as part of the
        // channel's name the way it does everywhere else this convention is used.
        Text(
            "#",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            channel.name.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected || unread) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected || unread) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (muted) {
            Icon(
                Icons.Filled.NotificationsOff,
                contentDescription = "Muted",
                modifier = Modifier.size(14.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (unread) {
            Box(
                Modifier.size(7.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

@Composable
private fun SidebarSectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 4.dp),
    )
}

// ---------------------------------------------------------------------------
// Account bar
// ---------------------------------------------------------------------------

/**
 * The fixed strip at the bottom: who you are, and the three places that aren't a conversation.
 *
 * Fixed height and its own surface tint, so it stays put while the list above it scrolls —
 * which is the entire point of putting it here rather than above a list of unknown length.
 */
@Composable
private fun ProfileBar(
    state: AppState,
    onOpenStories: () -> Unit,
    onOpenSessions: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenMentions: () -> Unit,
) {
    var statusMenu by remember { mutableStateOf(false) }
    val me = state.currentUser ?: return

    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Row(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { statusMenu = true }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AvatarWithStatus(me, state.myStatus, 32)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        me.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Custom status line: emoji + text when set, plain presence label otherwise.
                    val custom = state.customStatusOf(me.id)
                    if (custom == null) {
                        Text(
                            statusLabel(state.myStatus),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    } else {
                        Text(
                            custom,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            DropdownMenu(expanded = statusMenu, onDismissRequest = { statusMenu = false }) {
                // The handle lives at the top of your own menu: it is yours to hand out, so
                // it belongs where you go looking for it, not on every row of every list.
                DropdownMenuItem(
                    enabled = false,
                    text = { Text(me.handle, style = MaterialTheme.typography.labelMedium) },
                    onClick = {},
                )
                HorizontalDivider()
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

        Spacer(Modifier.weight(1f))

        // Compact icons: the bar is 260dp wide and these four have to fit beside a name.
        listOf(
            Triple(Icons.Filled.AlternateEmail, "Mentions", onOpenMentions),
            Triple(Icons.Filled.AmpStories, "Stories", onOpenStories),
            Triple(Icons.Filled.Devices, "Devices and sign-ins", onOpenSessions),
            Triple(Icons.Filled.Settings, "Settings", onOpenSettings),
        ).forEach { (icon, label, action) ->
            IconButton(onClick = action, modifier = Modifier.size(32.dp)) {
                Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Conversation
// ---------------------------------------------------------------------------

@Composable
private fun Conversation(state: AppState, composerFocus: FocusRequester) {
    var draft by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val channel = state.selectedChannel ?: return
    val other = channel.members.firstOrNull { it.id != state.currentUser?.id }

    // @-autocomplete state. The token is recomputed from the draft on every change; the
    // popup is shown exactly while a token is active and something matches it.
    val isGuildChannel = state.selectedGuild?.channels?.any { it.id == channel.id } == true

    // The emoji panel and the reaction sheet. One `pickerTarget` field drives both: null =
    // closed, "composer" = inserting into the draft, anything else = reacting to that message.
    var pickerTarget by remember { mutableStateOf<String?>(null) }
    val recents = rememberRecentEmoji()

    // Resolves <@id> and friends for rendering. Built from every user the client knows about
    // in this conversation plus the guild's roles/channels, and rebuilt when they change.
    val resolver = remember(state.messages, state.channelMembers, state.selectedGuild, state.currentUser?.id) {
        val users = buildMap {
            state.messages.forEach { put(it.author.id, it.author) }
            channel.members.forEach { put(it.id, it) }
            state.channelMembers.forEach { put(it.user.id, it.user) }
        }
        val roles = state.selectedGuild?.roles
            ?.associate { it.id to it.name } ?: emptyMap()
        val channelNames = state.selectedGuild?.channels
            ?.filter { !it.isCategory && it.name != null }
            ?.associate { it.id to it.name!! } ?: emptyMap()
        MentionResolver(users, roles, channelNames, state.currentUser?.id)
    }

    LaunchedEffect(state.messages.size, channel.id) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    // Opening a conversation puts the caret in the message box. Anything else means the first
    // thing you do after clicking a chat is click again, and it also leaves Escape with
    // nothing focused to travel down from.
    LaunchedEffect(channel.id) { runCatching { composerFocus.requestFocus() } }

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

        Box(Modifier.weight(1f)) {
            MessageList(
                messages = state.messages,
                selfId = state.currentUser?.id,
                layout = if (state.chatLayout == "COMPACT") ChatLayout.COMPACT else ChatLayout.BUBBLES,
                listState = listState,
                resolver = resolver,
                onReact = { messageId, emoji -> state.toggleReaction(messageId, emoji) },
                onMessageLongPress = { message -> pickerTarget = message.id },
                modifier = Modifier.fillMaxSize(),
            )
        }

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

        // -- Composer --------------------------------------------------------

        // The anchored autocomplete popup, drawn over the composer when an @token is active.
        // The caret is approximated as the end of the draft — the compose text field doesn't
        // expose caret position through onValueChange, and typing at the end is the normal case.
        val token = activeMentionToken(draft, draft.length)
        val candidates = if (token != null) {
            mentionCandidates(
                query = token.query,
                members = state.channelMembers,
                dmMembers = channel.members,
                roles = state.selectedGuild?.roles ?: emptyList(),
                isGuildChannel = isGuildChannel,
            )
        } else emptyList()

        Box {
            Column {
                if (pickerTarget == "composer") {
                    EmojiPicker(
                        recents = recents,
                        onPick = { emoji ->
                            draft += emoji
                            state.onTyping()
                        },
                        onClose = { pickerTarget = null },
                        modifier = Modifier
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                            .fillMaxWidth(),
                    )
                }

                if (pickerTarget != null && pickerTarget != "composer") {
                    ReactionSheet(
                        recents = recents,
                        onPick = { emoji ->
                            pickerTarget?.let { messageId ->
                                state.toggleReaction(messageId, emoji)
                            }
                            pickerTarget = null
                        },
                        onDismiss = { pickerTarget = null },
                    )
                }

                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    IconButton(
                        onClick = {
                            pickerTarget = if (pickerTarget == "composer") null else "composer"
                        },
                    ) {
                        Text("😀", fontSize = 22.sp)
                    }
                    Spacer(Modifier.width(4.dp))

                    Column(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = {
                                draft = it
                                // Throttled inside AppState — one mutation per 3s, not per keystroke.
                                if (it.isNotBlank()) state.onTyping()
                            },
                            placeholder = { Text("Message  ·  @ to mention · Shift+Enter for a new line") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(composerFocus)
                                // onPreviewKeyEvent, not onKeyEvent: the field must never see the Enter that
                                // sends, or it inserts a newline first and leaves a blank line behind.
                                .onPreviewKeyEvent { event ->
                                    val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                                    when {
                                        !event.isPress -> false
                                        !isEnter -> false
                                        // Enter with the autocomplete open picks the top candidate.
                                        candidates.isNotEmpty() -> {
                                            applyMention(draft, token!!, candidates.first()).let { (text, _) ->
                                                draft = text
                                            }
                                            true
                                        }
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

                        // The autocomplete list, anchored under the field. Shown only while a
                        // token is active and something matches.
                        if (candidates.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                tonalElevation = 4.dp,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                            ) {
                                Column {
                                    candidates.forEach { candidate ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    val (text, _) = applyMention(draft, token!!, candidate)
                                                    draft = text
                                                }
                                                .padding(horizontal = 12.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Avatar(
                                                when (candidate) {
                                                    is MentionCandidate.User -> candidate.user.id
                                                    is MentionCandidate.Role -> candidate.id
                                                    is MentionCandidate.Special -> candidate.keyword
                                                },
                                                when (candidate) {
                                                    is MentionCandidate.User -> candidate.user.label
                                                    is MentionCandidate.Role -> candidate.label
                                                    is MentionCandidate.Special -> "@"
                                                },
                                                size = 24,
                                            )
                                            Spacer(Modifier.width(10.dp))
                                            Column {
                                                Text(
                                                    candidate.label,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                candidate.detail?.let { detail ->
                                                    if (detail.isNotBlank()) {
                                                        Text(
                                                            detail,
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(Modifier.width(4.dp))
                    IconButton(
                        onClick = { state.attachAndSend(draft); draft = "" },
                        enabled = state.uploadProgress == null,
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach a file")
                    }
                    IconButton(
                        onClick = { if (draft.isNotBlank()) { state.send(draft); draft = "" } },
                        enabled = draft.isNotBlank(),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                    }
                }
            }
        }
    }
}

/**
 * The reaction sheet: the quick one-tap set plus the full picker behind "More", shown when a
 * message is long-pressed. Eight defaults cover nearly every reaction anyone sends; the grid
 * is there for the other ones.
 */
@Composable
private fun ReactionSheet(
    recents: androidx.compose.runtime.MutableState<List<String>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    if (expanded) {
        EmojiPicker(
            recents = recents,
            onPick = onPick,
            onClose = onDismiss,
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .fillMaxWidth(),
        )
        return
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val font = emojiFontFamily()
            QUICK_REACTIONS.forEach { emoji ->
                Text(
                    emoji,
                    fontFamily = font,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPick(emoji) }
                        .padding(6.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { expanded = true }) { Text("More") }
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
private fun EmptyState(state: AppState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No conversation open", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.padding(4.dp))
            Text(
                // Says what to do *here*, which depends on where you are — telling someone
                // in a server to enter a handle was advice for a different screen.
                if (state.selectedGuild != null) "Pick a channel on the left."
                else "Open the Friends tab to add someone by handle.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
