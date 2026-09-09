package app.singular.client.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.AmpStories
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.input.key.Key
import kotlinx.coroutines.launch
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.singular.client.AppState
import app.singular.client.net.ChannelDto
import app.singular.client.net.GuildDto
import app.singular.client.net.MessageDto
import app.singular.client.net.UserDto

/**
 * Places a popup directly above its anchor, right edges aligned.
 *
 * Compose's built-in `Popup(alignment = …)` positions *within* the parent's bounds, which is
 * no help when the panel is taller than the composer it belongs to. This measures the real
 * content and puts its bottom-right corner on the anchor's top-right — so the picker always
 * sits just clear of the composer, at whatever height it happens to be.
 *
 * Both axes are clamped into the window. A popup opened near the top of a short window would
 * otherwise be positioned off-screen and simply never appear, which reads as a dead button.
 */
private val AboveAnchor = object : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x = (anchorBounds.right - popupContentSize.width)
            .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        val y = (anchorBounds.top - popupContentSize.height)
            .coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}

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
      // The Android system back runs the same chain as Escape: overlay interceptors (reaction
      // sheet, autocomplete — registered inside Conversation) first, then this screen's own
      // layer of closing the open conversation.
      val backDispatcher = LocalBackDispatcher.current
      SystemBackHandler(enabled = state.selectedChannel != null) {
        if (backDispatcher?.dispatch() != true) state.closeChannel()
      }
      Row(Modifier.fillMaxSize()) {
        // Requested a frame late, once the Friends tab has actually placed the field.
        if (pendingHandleFocus) {
            LaunchedEffect(Unit) {
                runCatching { handleFocus.requestFocus() }
                pendingHandleFocus = false
            }
        }

        // In a narrow window the sidebar and the conversation take turns rather than both
        // being squeezed: 72dp of rail plus 260dp of sidebar leaves a conversation column too
        // thin to read below about 760dp. Which one shows follows what you're doing — a
        // conversation open means you're reading it; Escape (which clears the selection) takes
        // you back to the list.
        val compact = LocalWindowWidth.current.isCompact
        val showSidebar = !compact || state.selectedChannel == null

        ServerRail(state, Modifier.width(panelWidth(expanded = 72.dp, medium = 72.dp, compact = 60.dp)))

        if (showSidebar) {
            ChannelSidebar(
                state, onOpenSessions, onOpenSettings, onOpenStories, onOpenMentions,
                onOpenServerSettings, handleFocus,
                tab, onTabChange = { tab = it },
                // Fills what's left when it's the only pane, so a narrow window shows a full
                // list rather than a 260dp column beside dead space.
                if (compact) Modifier.weight(1f).fillMaxHeight()
                else Modifier.width(state.sidebarWidthDp.dp).fillMaxHeight(),
            )

            // A draggable divider: wide enough to be grabbable, visually a hairline. Dragging
            // it resizes the sidebar, which is the one thing every desktop chat client lets
            // you do that this one didn't.
            ResizableDivider(
                widthDp = state.sidebarWidthDp,
                onResize = { state.sidebarWidthDp = it },
            )
        }

        if (!compact || !showSidebar) {
            Box(Modifier.weight(1f).fillMaxHeight()) {
                // No crossfade here, deliberately: the message list is shared AppState that is
                // replaced on channel switch, so an outgoing pane would render the *new*
                // channel's data mid-transition — a fade from one thing to itself. Route
                // transitions (App) and list items animate; conversation switching stays
                // instant, which is also what every chat app does.
                if (state.selectedChannel == null) HomeEmptyState(state)
                else Conversation(state, composerFocus, showBack = compact)
            }
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
internal fun AvatarWithStatus(user: UserDto, status: String, size: Int) {
    Box(Modifier.size(size.dp)) {
        Avatar(user, size)
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
    var creatingGroup by remember { mutableStateOf(false) }

    if (creatingGroup) {
        GroupDmDialog(
            state = state,
            onDismiss = { creatingGroup = false },
            onCreate = { userIds, name ->
                creatingGroup = false
                state.createGroupDm(userIds, name)
            },
        )
    }

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

        // A pager, not a `when`: swiping and tapping land on the same page, and the indicator
        // animates across rather than two instant swaps. The two pages are different enough —
        // a list of chats and a form — that this reads as one home with two halves, not two
        // screens stacked.
        val pagerState = rememberPagerState(initialPage = tab.ordinal, pageCount = { 2 })

        // Tab → page and page → tab, kept in one place so they can never disagree about which
        // half is showing. Swiping is direct manipulation, so it is not gated on reduced
        // motion; only the tap-triggered animated scroll is. The reduced-motion read happens
        // at composition time (it must — it's a composable), and the effect captures it.
        val reducedMotion = LocalReducedMotion.current
        LaunchedEffect(tab) {
            if (pagerState.currentPage != tab.ordinal) {
                if (reducedMotion) pagerState.scrollToPage(tab.ordinal)
                else pagerState.animateScrollToPage(tab.ordinal)
            }
        }
        LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
            if (!pagerState.isScrollInProgress && pagerState.currentPage != tab.ordinal) {
                onTabChange(HomeTab.entries[pagerState.currentPage])
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
        ) { page ->
            when (page) {
                0 -> ConversationList(state, onNewGroup = { creatingGroup = true })
                else -> FriendsTab(state, handleFocus, onNewGroup = { creatingGroup = true })
            }
        }
    }
}

@Composable
private fun ConversationList(state: AppState, onNewGroup: () -> Unit) {
    if (state.channels.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.Chat,
                title = "No conversations yet",
                hint = "Open the Friends tab to start one.",
                actionLabel = "Or start a group",
                onAction = onNewGroup,
            )
        }
        return
    }

    // A resolver for the sidebar, built from the people in your conversations. The message
    // list has a richer one (it also knows message authors and the open server's roles), but
    // the sidebar can't reach that — and a preview reading `You: <@221239599735771136>` is not
    // a preview, so it needs its own rather than going without.
    val previewResolver = remember(state.channels, state.currentUser?.id) {
        MentionResolver(
            usersById = buildMap {
                state.channels.forEach { c -> c.members.forEach { put(it.id, it) } }
                state.currentUser?.let { put(it.id, it) }
            },
            rolesById = emptyMap(),
            channelsById = state.channels.mapNotNull { c -> c.name?.let { c.id to it } }.toMap(),
            selfId = state.currentUser?.id,
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(state.channels, key = { it.id }) { channel ->
            // animateItem() is LazyItemScope-scoped — it must live inside the item lambda.
            val itemMod = if (LocalReducedMotion.current) Modifier else Modifier.animateItem()
            Box(itemMod) {
                DirectMessageRow(
                    channel = channel,
                    selfId = state.currentUser?.id,
                    status = channel.members.firstOrNull { it.id != state.currentUser?.id }
                        ?.let(state::statusOf) ?: "OFFLINE",
                    preview = state.lastMessages[channel.id]
                        ?.preview(state.currentUser?.id, previewResolver::displayFor),
                    muted = state.mutedChannels[channel.id] == true,
                    unread = state.unread[channel.id] == true,
                    mentions = state.mentionCounts[channel.id] ?: 0,
                    selected = channel.id == state.selectedChannel?.id,
                    onClick = { state.openChannel(channel) },
                )
            }
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
private fun FriendsTab(state: AppState, handleFocus: FocusRequester, onNewGroup: () -> Unit) {
    var handle by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current

    // Everyone you already have a 1:1 conversation with. That is the whole friend list this
    // app has — there is no accept/decline request flow yet, and inventing an empty one here
    // would be a screen that never has anything in it.
    val friends = state.dmContacts()

    val open = { state.openDmWithHandle(handle.trim()); handle = "" }

    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = handle,
                    onValueChange = { handle = it },
                    label = { Text("Add by handle") },
                    placeholder = { Text("orbit#2989") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(handleFocus)
                        .formField(focus, enabled = handle.contains('#'), onConfirm = open),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { open() }),
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onNewGroup) {
                    Icon(
                        Icons.Filled.GroupAdd,
                        contentDescription = "New group conversation",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
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
                        .then(if (LocalReducedMotion.current) Modifier else Modifier.animateItem())
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
    mentions: Int,
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

        // The badge replaces the plain dot when there is one: two marks on the same row
        // compete, and the red one is strictly more informative.
        if (mentions > 0) {
            MentionBadge(mentions)
            Spacer(Modifier.width(4.dp))
        } else if (unread) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface)
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
    // While a category is folded shut it speaks for its children: their unread state and their
    // mention count surface on the header, because a badge on a row nobody can see is the same
    // as no badge. Expanding hands both back to the individual channels.
    val rolledUpUnread = isCollapsed && state.categoryHasUnread(channels)
    val rolledUpMentions = if (isCollapsed) state.categoryMentionCount(channels) else 0

    item(key = "cat-$id") {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 8.dp, end = 12.dp, top = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The chevron rotates rather than swapping glyphs, so the control reads as the
            // same object in both states. Snapped under reduced motion.
            val reducedMotion = LocalReducedMotion.current
            val turn by animateFloatAsState(
                targetValue = if (isCollapsed) -90f else 0f,
                animationSpec = if (reducedMotion) snap() else tween(Motion.BASE),
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
                // Brighter on dark, darker on light — `onSurface` against `onSurfaceVariant`
                // is exactly that in both modes, so it needs no per-theme branch.
                color = if (rolledUpUnread) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(6.dp))
            MentionBadge(rolledUpMentions)
        }
    }

    if (isCollapsed) return

    items(channels, key = { it.id }) { channel ->
        GuildChannelRow(
            channel = channel,
            selected = channel.id == state.selectedChannel?.id,
            unread = state.unread[channel.id] == true,
            mentions = state.mentionCounts[channel.id] ?: 0,
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
    mentions: Int,
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
        // Brighter on dark, darker on light: `onSurface` is the high-contrast ink in both
        // modes and `onSurfaceVariant` the muted one, so one expression covers both themes.
        val ink =
            if (selected || unread) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant

        // The hash is drawn as text at the same size as the name, so it reads as part of the
        // channel's name the way it does everywhere else this convention is used. It brightens
        // with the name — leaving it muted made an unread channel look half-lit.
        Text(
            "#",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = ink,
        )
        Spacer(Modifier.width(7.dp))
        Text(
            channel.name.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected || unread) FontWeight.SemiBold else FontWeight.Normal,
            color = ink,
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
        } else {
            // The badge replaces the plain dot when there is one — two marks for the same row
            // compete, and the red one is strictly more informative.
            if (mentions > 0) MentionBadge(mentions)
            else if (unread) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface)
                )
            }
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
private fun Conversation(
    state: AppState,
    composerFocus: FocusRequester,
    /** True when this pane replaced the sidebar, so it must offer a way back to it. */
    showBack: Boolean = false,
) {
    // Drafts are kept per channel: one shared `remember` meant switching chats carried the
    // half-written message into the wrong conversation, and a bare `remember(channel.id)`
    // meant leaving and returning threw it away. The map gives each channel its own draft for
    // as long as this screen is alive.
    val drafts = remember { mutableStateMapOf<String, String>() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val channel = state.selectedChannel ?: return
    val other = channel.members.firstOrNull { it.id != state.currentUser?.id }
    var draft by remember(channel.id) { mutableStateOf(drafts[channel.id].orEmpty()) }
    LaunchedEffect(channel.id, draft) { drafts[channel.id] = draft }

    // @-autocomplete state. The token is recomputed from the draft on every change; the
    // popup is shown exactly while a token is active and something matches it.
    val isGuildChannel = state.selectedGuild?.channels?.any { it.id == channel.id } == true

    // The emoji panel and the reaction sheet. One `pickerTarget` field drives both: null =
    // closed, "composer" = inserting into the draft, anything else = reacting to that message.
    var pickerTarget by remember { mutableStateOf<String?>(null) }
    val recents = rememberRecentEmoji()

    // Edit-in-place. Non-null while the composer is in "editing message X" mode: the field is
    // pre-filled with that message's text, Enter commits the edit (not a new send), and Esc or
    // a channel switch abandons it. Kept per channel with the drafts so switching conversations
    // can't commit an edit into the wrong chat — the same bug drafts exist to prevent.
    var editingMessage by remember(channel.id) { mutableStateOf<MessageDto?>(null) }

    // Entering edit mode seeds the field with the message's current text. A separate effect
    // rather than doing it at the call site, because the long-press sheet only sets the state;
    // keeping every transition through one place means Esc-abandon and Cancel-abandon clear
    // the draft identically too.
    LaunchedEffect(editingMessage?.id) {
        val target = editingMessage ?: return@LaunchedEffect
        draft = target.content.orEmpty()
        runCatching { composerFocus.requestFocus() }
    }

    // In-channel search. Results are held as plain ids + previews rather than rendered
    // MessageDtos so the strip stays a summary; tapping a result scrolls to it if it's in
    // the loaded window, and says so when it isn't (older than the loaded page).
    var searching by remember(channel.id) { mutableStateOf(false) }
    var searchQuery by remember(channel.id) { mutableStateOf("") }
    var searchResults by remember(channel.id) { mutableStateOf<List<MessageDto>?>(null) }

    // Debounced: one query per pause in typing, not per keystroke — search is a
    // rate-limited server operation, and hammering it with "a", "ap", "app" burns the
    // bucket before the user has finished typing the word.
    LaunchedEffect(searchQuery, channel.id) {
        if (!searching) return@LaunchedEffect
        if (searchQuery.isBlank()) {
            searchResults = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(350)
        state.searchChannel(searchQuery) { results ->
            searchResults = results
        }
    }

    // Closing the strip clears results so reopening doesn't show the previous query's
    // stale matches over a different one.
    LaunchedEffect(searching) {
        if (!searching) {
            searchQuery = ""
            searchResults = null
        }
    }

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
        MentionResolver(
            users, roles, channelNames, state.currentUser?.id,
            myRoleIds = state.selectedGuild?.me?.roles?.map { it.id }?.toSet().orEmpty(),
        )
    }

    // The plate behind a mention, in the composer and the message list alike.
    val mentionTint = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)

    // -- Scroll following ------------------------------------------------------
    //
    // Follow new messages only while the user is already at the bottom. Scrolling up to read
    // history must not be interrupted by a message arriving — "the chat yanked me back" is
    // the single most-read bug a chat client can ship. When they scroll up, we stop following
    // and show a pill instead; it counts what arrived while they were away.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf true
            lastVisible >= state.messages.lastIndex - 2
        }
    }
    var unseenBelow by remember(channel.id) { mutableStateOf(0) }
    val lastSeenSize = remember(channel.id) { mutableStateOf(0) }

    LaunchedEffect(state.messages.size, channel.id) {
        val previous = lastSeenSize.value
        lastSeenSize.value = state.messages.size
        when {
            state.messages.isEmpty() -> Unit
            atBottom -> {
                listState.animateScrollToItem(state.messages.lastIndex)
                unseenBelow = 0
            }
            state.messages.size > previous && previous != 0 -> {
                unseenBelow += state.messages.size - previous
            }
        }
    }

    // Reset the counter on channel open so a fresh conversation never shows a stale count.
    LaunchedEffect(channel.id) {
        unseenBelow = 0
        lastSeenSize.value = state.messages.size
        runCatching { composerFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(
                start = if (showBack) 4.dp else 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Only when this pane has replaced the sidebar. Escape already does this, but a
            // layout that hides the list needs a visible way back — a keyboard shortcut is not
            // an affordance.
            if (showBack) {
                IconButton(onClick = { state.closeChannel() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to conversations",
                    )
                }
            }
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

            // In-channel search. A plain icon toggle rather than a persistent field: the
            // header is narrow on phones, and search is an occasional action, not a mode
            // anyone lives in — the field appears only when asked for.
            IconButton(onClick = { searching = !searching }) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = if (searching) "Close search" else "Search in conversation",
                    tint = if (searching) MaterialTheme.colorScheme.primary
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

        // The search strip, directly under the header when open. Debounced inside
        // AppState.searchChannel's caller below — see the LaunchedEffect.
        if (searching) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search this conversation") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    onClick = { searchQuery = "" },
                    enabled = searchQuery.isNotBlank(),
                ) { Text("Clear") }
            }
            searchResults?.let { results ->
                Text(
                    if (results.isEmpty()) "No matches in this channel"
                    else "${results.size} ${if (results.size == 1) "match" else "matches"}, newest first",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 4.dp),
                )
            }
            HorizontalDivider()
        }

        HorizontalDivider()

        Box(Modifier.weight(1f)) {
            if (state.loadingChannel && state.messages.isEmpty()) {
                // Placeholder rows, only ever during the first page of a channel — never a
                // shimmer, never while scrolling. Six static rows the reader can ignore at a
                // glance, which is the whole point: a skeleton should say "something is coming"
                // and get out of the way, not pretend to be content.
                SkeletonMessages()
            } else {
                MessageList(
                    messages = state.messages,
                    selfId = state.currentUser?.id,
                    layout = if (state.chatLayout == "COMPACT") ChatLayout.COMPACT else ChatLayout.BUBBLES,
                    listState = listState,
                    resolver = resolver,
                    onReact = { messageId, emoji -> state.toggleReaction(messageId, emoji) },
                    onMessageLongPress = { message -> pickerTarget = message.id },
                    modifier = Modifier.fillMaxSize(),
                    isPending = { state.isPending(it) },
                    isFailed = { state.isFailedSend(it) },
                    onRetry = { message ->
                        // The snackbar owns the retry prompt; tapping a failed message is a
                        // shortcut to the same place.
                        state.retryFailed(message)
                    },
                )
            }

            // The way back to the present. Only meaningful while history is open — at the
            // bottom the pill is the thing it would jump to, so it hides there.
            if (unseenBelow > 0 && !atBottom) {
                JumpToPresentPill(
                    count = unseenBelow,
                    onClick = {
                        scope.launch {
                            if (state.messages.isNotEmpty()) {
                                listState.animateScrollToItem(state.messages.lastIndex)
                            }
                            unseenBelow = 0
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                )
            }
        }

        state.uploadProgress?.let { progress ->
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
        }

        TypingIndicator(state.typingUsers.values.toList())

        // Transient failures surface through the snackbar host (App.kt), not inline text —
        // an error that parks itself above the composer reads as furniture, and "Reconnecting"
        // would sit there after the reconnect already succeeded.

        HorizontalDivider()

        // -- Composer --------------------------------------------------------

        // The anchored autocomplete popup, drawn over the composer when an @token is active.
        // The caret is approximated as the end of the draft — the compose text field doesn't
        // expose caret position through onValueChange, and typing at the end is the normal case.
        val token = activeMentionToken(draft, draft.length)

        // Escape closes the popup without clearing what you typed. Keyed on the token's start
        // so beginning a *new* mention re-opens it — otherwise one Escape would suppress
        // autocomplete for the rest of the message.
        var dismissedAt by remember { mutableStateOf<Int?>(null) }
        if (token == null) dismissedAt = null

        val candidates = if (token != null && dismissedAt != token.start) {
            mentionCandidates(
                query = token.query,
                members = state.channelMembers,
                dmMembers = channel.members,
                roles = state.selectedGuild?.roles ?: emptyList(),
                isGuildChannel = isGuildChannel,
            )
        } else emptyList()

        // Which row the arrows are on. Reset whenever the query changes, because the list
        // underneath has been rebuilt and index 3 of the old list means nothing in the new one.
        var highlighted by remember { mutableStateOf(0) }
        remember(token?.start, token?.query) { highlighted = 0; true }
        val active = highlighted.coerceIn(0, (candidates.size - 1).coerceAtLeast(0))

        val accept = { candidate: MentionCandidate ->
            val (text, _) = applyMention(draft, token!!, candidate)
            draft = text
            dismissedAt = null
        }

    // Feature 6. The recorder is created per take inside RecordingBar and torn down with it —
    // see AudioRecorder for why a shared, always-open microphone is not an option.
    var recording by remember { mutableStateOf(false) }

    // The conversation-level layers Escape must peel off before it closes the channel:
    // recording, then the reaction sheet, then the mention autocomplete. Registered with the
    // back dispatcher because an ancestor's preview handler sees Escape first — the sheet is
    // inline (not a popup window), so without this the key never reaches it and the whole
    // conversation closes instead.
    BackHandler(enabled = recording) {
        recording = false
        true
    }
    BackHandler(
        enabled = (pickerTarget != null && pickerTarget != "composer") || candidates.isNotEmpty(),
    ) {
            when {
                pickerTarget != null && pickerTarget != "composer" -> {
                    pickerTarget = null
                    true
                }
                candidates.isNotEmpty() -> {
                    dismissedAt = token?.start
                    true
                }
                else -> false
            }
        }

        Box {
            // A real Popup, not an offset Box.
            //
            // Two things an in-tree overlay could not do. **Z-order**: a Box drawn inside the
            // composer still shares the conversation's draw order, so message bubbles above it
            // painted straight over the panel. A Popup composes into its own layer, above
            // everything in the window, which is the whole reason the API exists.
            //
            // **Placement**: the offset version used a hand-tuned -282dp to clear the
            // composer, which was a magic number that would drift the moment the panel's
            // height changed. AboveAnchor measures the real content and puts its bottom-right
            // corner on the composer's top-right, so it is correct at any size.
            if (pickerTarget == "composer") {
                Popup(
                    popupPositionProvider = AboveAnchor,
                    onDismissRequest = {
                        pickerTarget = null
                        // Hand focus back to where you were typing. The popup took it in order
                        // to hear the outside click, so without this the composer is left
                        // unfocused and the next keystroke goes nowhere.
                        runCatching { composerFocus.requestFocus() }
                    },
                    // `focusable = true` is what makes clicking away close it. A non-focusable
                    // popup never receives the outside click at all, so `onDismissRequest`
                    // simply never fires — which is why the panel stayed open. It also brings
                    // Escape along, since a focusable popup gets the key first.
                    properties = PopupProperties(focusable = true),
                ) {
                    EmojiPicker(
                        recents = recents,
                        onPick = { emoji ->
                            draft += emoji
                            state.onTyping()
                            // The popup holds focus while it's open; hand it back so the next
                            // keystroke after a pick lands in the message box rather than
                            // going nowhere.
                            runCatching { composerFocus.requestFocus() }
                        },
                        onClose = { pickerTarget = null },
                        // Never wider than the window it floats over, or a narrow window
                        // gets a panel clipped at both edges.
                        modifier = Modifier.width(
                            cappedWidth(max = 320.dp, fractionOfWindow = 0.9f, floor = 240.dp)
                        ),
                    )
                }
            }

            Column {
                if (pickerTarget != null && pickerTarget != "composer") {
                    // Not a Popup: the sheet sits inline above the composer and must not steal
                    // the composer's focus, which is also why Escape reaches it through the
                    // back dispatcher (registered above) rather than a key handler here.
                    val targetMessage = state.messages.firstOrNull { it.id == pickerTarget }
                    ReactionSheet(
                        recents = recents,
                        onPick = { emoji ->
                            pickerTarget?.let { messageId ->
                                state.toggleReaction(messageId, emoji)
                            }
                            pickerTarget = null
                        },
                        onDismiss = { pickerTarget = null },
                        modifier = Modifier.animateEntrance(),
                        actions = targetMessage?.let { message ->
                            MessageSheetActions(
                                canEdit = message.author.id == state.currentUser?.id,
                                pinned = state.pinnedIds.contains(message.id),
                                onEdit = {
                                    pickerTarget = null
                                    editingMessage = message
                                },
                                onDelete = {
                                    pickerTarget = null
                                    state.deleteMessage(message.id)
                                },
                                onTogglePin = {
                                    pickerTarget = null
                                    state.togglePin(message.id)
                                },
                            )
                        },
                    )
                }

                if (recording) {
                    // Replaces the composer entirely: while you're recording, the text box has
                    // nothing to do, and showing both invites half-finished messages.
                    val active = remember { app.singular.client.platform.AudioRecorder() }
                    DisposableEffect(Unit) { onDispose { active.cancel() } }
                    RecordingBar(
                        recorder = active,
                        onCancel = { recording = false },
                        onSend = { audio ->
                            recording = false
                            state.recordAndSend(audio)
                        },
                        onError = { message ->
                            recording = false
                            state.reportError(message)
                        },
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                    Column(Modifier.weight(1f)) {
                        OutlinedTextField(
                            value = draft,
                            onValueChange = {
                                draft = it
                                // Throttled inside AppState — one mutation per 3s, not per keystroke.
                                if (it.isNotBlank()) state.onTyping()
                            },
                            label = {
                                // The label is the only editing affordance inside the field
                                // itself: it says what Enter will do, which changes.
                                if (editingMessage != null) Text("Editing message")
                            },
                            placeholder = {
                                Text(
                                    "Message  ·  @ to mention · Shift+Enter for a new line",
                                    color = LocalSingularColors.current.textFaint,
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(composerFocus)
                                // onPreviewKeyEvent, not onKeyEvent: the field must never see the Enter that
                                // sends, or it inserts a newline first and leaves a blank line behind.
                                .onPreviewKeyEvent { event ->
                                    val isEnter = event.key == Key.Enter || event.key == Key.NumPadEnter
                                    val open = candidates.isNotEmpty()
                                    when {
                                        !event.isPress -> false

                                        // While the popup is open the arrows belong to it, not
                                        // to the text field — the caret is inside a token you
                                        // are still choosing, so there is nowhere useful for
                                        // Up/Down to move it anyway. Wrapping rather than
                                        // clamping: eight rows is short enough that running off
                                        // one end and reappearing at the other beats reversing.
                                        open && event.key == Key.DirectionDown -> {
                                            highlighted = (active + 1) % candidates.size; true
                                        }
                                        open && event.key == Key.DirectionUp -> {
                                            highlighted = (active - 1 + candidates.size) % candidates.size
                                            true
                                        }
                                        open && event.key == Key.Tab -> {
                                            accept(candidates[active]); true
                                        }
                                        open && event.key == Key.Escape -> {
                                            dismissedAt = token?.start; true
                                        }
                                        open && isEnter -> { accept(candidates[active]); true }

                                        // Abandoning an edit: Esc with the popup closed.
                                        // Distinct from the popup branch above by `open`, and
                                        // deliberately before the !isEnter fallthrough so it
                                        // cannot leak into the send path.
                                        editingMessage != null && event.key == Key.Escape -> {
                                            editingMessage = null
                                            draft = ""
                                            true
                                        }

                                        !isEnter -> false
                                        // Shift+Enter falls through so the field inserts the newline itself.
                                        event.isShiftPressed -> false
                                        else -> {
                                            val editing = editingMessage
                                            if (editing != null) {
                                                // Editing replaces the body; an empty edit is
                                                // a cancel, not "wipe the message" — that is
                                                // what Delete is for.
                                                if (draft.isNotBlank()) {
                                                    state.editMessage(editing.id, draft)
                                                }
                                                editingMessage = null
                                                draft = ""
                                            } else if (draft.isNotBlank()) {
                                                state.send(draft); draft = ""
                                            }
                                            true
                                        }
                                    }
                                },
                            // Draws `@Orbit` over the `<@221…>` the draft actually holds. The
                            // value stays in the wire format — see MentionVisualTransformation.
                            visualTransformation = remember(resolver.revision, mentionTint) {
                                MentionVisualTransformation(resolver, mentionTint)
                            },
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                val editing = editingMessage
                                if (editing != null) {
                                    if (draft.isNotBlank()) state.editMessage(editing.id, draft)
                                    editingMessage = null
                                    draft = ""
                                } else if (draft.isNotBlank()) {
                                    state.send(draft); draft = ""
                                }
                            }),
                        )

                        // The editing banner: the field label is subtle by design, so this row
                        // is what actually announces the mode change — especially on mobile,
                        // where there is no Esc key and Cancel needs a visible target.
                        if (editingMessage != null) {
                            Row(
                                Modifier.padding(top = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Editing message",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = {
                                    editingMessage = null
                                    draft = ""
                                    runCatching { composerFocus.requestFocus() }
                                }) { Text("Cancel") }
                            }
                        }

                        // The autocomplete list, anchored under the field. Shown only while a
                        // token is active and something matches.
                        if (candidates.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                tonalElevation = 4.dp,
                                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                                modifier = Modifier
                                    .padding(top = 4.dp)
                                    .fillMaxWidth()
                                    .animateEntrance(),
                            ) {
                                Column {
                                    candidates.forEachIndexed { index, candidate ->
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                // The arrow selection and the click target are
                                                // the same row, so keyboard and mouse can't
                                                // disagree about what "this one" means.
                                                .background(
                                                    if (index == active)
                                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                                    else Color.Transparent
                                                )
                                                .clickable { accept(candidate) }
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
                    // Emoji, then attach, then record, then send. All four act on the message
                    // being written, so they belong on the same side of the field — the emoji
                    // button alone on the left split one group of controls across two places.
                    IconButton(
                        onClick = {
                            pickerTarget = if (pickerTarget == "composer") null else "composer"
                        },
                    ) {
                        Text("😀", fontSize = 22.sp)
                    }
                    IconButton(
                        onClick = { state.attachAndSend(draft); draft = "" },
                        enabled = state.uploadProgress == null,
                    ) {
                        Icon(Icons.Filled.AttachFile, contentDescription = "Attach a file")
                    }
                    // Feature 6's second half. Record swaps the composer out rather than
                    // opening a dialog — a voice note is a message being written, exactly like
                    // the text it replaces.
                    IconButton(
                        onClick = { recording = true },
                        enabled = state.uploadProgress == null,
                    ) {
                        Icon(Icons.Filled.Mic, contentDescription = "Record a voice note")
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
}

/**
 * The reaction sheet: the quick one-tap set plus the full picker behind "More", shown when a
 * message is long-pressed. Eight defaults cover nearly every reaction anyone sends; the grid
 * is there for the other ones.
 *
 * The action row (edit/delete/pin) sits *under* the reaction strip rather than replacing it:
 * reacting is the most common long-press intent by far, and burying it behind a second sheet
 * would tax the many for the sake of the few.
 */
@Composable
private fun ReactionSheet(
    recents: androidx.compose.runtime.MutableState<List<String>>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Extra row actions for the long-pressed message. See [MessageSheetActions]. */
    actions: MessageSheetActions? = null,
) {
    var expanded by remember { mutableStateOf(false) }

    if (expanded) {
        EmojiPicker(
            recents = recents,
            onPick = onPick,
            onClose = onDismiss,
            modifier = modifier
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .fillMaxWidth(),
        )
        return
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 4.dp).fillMaxWidth(),
    ) {
        Column {
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

            actions?.let { row ->
                HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                Row(
                    Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Edit: authors only. The server enforces it too, but hiding the affordance
                    // for other people's messages is what stops a user from composing an edit
                    // only to have it bounce.
                    if (row.canEdit) {
                        TextButton(onClick = { row.onEdit() }) { Text("Edit") }
                    }
                    // Delete is offered on everything: in a guild, whether you may delete
                    // someone else's message is a MANAGE_MESSAGES question the server answers;
                    // in a DM it is author-only there too. Offering it and getting a clear
                    // refusal beats hiding it and leaving moderators without the gesture.
                    TextButton(onClick = { row.onDelete() }) { Text("Delete") }
                    TextButton(onClick = { row.onTogglePin() }) {
                        Text(if (row.pinned) "Unpin" else "Pin")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

/**
 * The non-reaction actions for the long-pressed message. A class rather than a pile of
 * nullable lambdas so a missing action is a missing row item, not a dead button.
 */
class MessageSheetActions(
    val canEdit: Boolean,
    val pinned: Boolean,
    val onEdit: () -> Unit,
    val onDelete: () -> Unit,
    val onTogglePin: () -> Unit,
)

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

        // The dots pulse only when motion is on; reduced motion shows a steady label.
        if (LocalReducedMotion.current) return@Row

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

/**
 * Placeholder rows shown while a channel's first page loads.
 *
 * Static, by design: a shimmer (animated gradient sweep) is one of the few animation patterns
 * that costs a full-screen redraw per frame, and a load spinner that cheap is one nobody asked
 * for. Six quiet rows in the layout the real messages will take is enough to say "wait one".
 */
@Composable
private fun SkeletonMessages() {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(6) { index ->
            val mine = index % 3 == 1
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(if (mine) 0.5f + (index % 2) * 0.1f else 0.4f + (index % 3) * 0.08f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                )
            }
        }
    }
}

/**
 * The sidebar's drag handle: 10dp wide, visually a 1dp hairline, cursor changes on hover.
 *
 * The width is persisted per device (`AppState.sidebarWidthDp`) — a 13-inch laptop and a
 * 34-inch monitor want different answers, and that's exactly why this isn't a synced setting.
 */
@Composable
private fun ResizableDivider(widthDp: Int, onResize: (Int) -> Unit) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hover by interaction.collectIsHoveredAsState()

    Box(
        Modifier
            .width(10.dp)
            .fillMaxHeight()
            .hoverable(interaction)
            .pointerInput(widthDp) {
                var startWidth = widthDp
                var accumulated = 0f
                detectDragGestures(
                    onDragStart = { startWidth = widthDp; accumulated = 0f },
                ) { change, dragAmount ->
                    change.consume()
                    accumulated += dragAmount.x
                    // Deltas arrive in pixels; the width is in dp. The density is read at
                    // composition time, which is the frame the pointer is in.
                    onResize(startWidth + (accumulated / density.density).toInt())
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(if (hover) 2.dp else 1.dp)
                .fillMaxHeight()
                .background(
                    if (hover) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outline
                )
        )
    }
}

/**
 * The "N new" pill that floats above the composer while history is open.
 *
 * Appears only when there's something to jump back to, which is also why it doesn't steal
 * attention at the bottom of a live chat — there it would just be noise.
 */
@Composable
private fun JumpToPresentPill(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        modifier = modifier.animateEntrance(),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (count == 1) "1 new message" else "$count new messages",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun HomeEmptyState(state: AppState) {
    EmptyState(
        icon = Icons.AutoMirrored.Filled.Chat,
        title = "No conversation open",
        // Says what to do *here*, which depends on where you are — telling someone in a server
        // to enter a handle was advice for a different screen.
        hint = if (state.selectedGuild != null) "Pick a channel on the left."
               else "Open the Friends tab to add someone by handle.",
        modifier = Modifier.fillMaxSize(),
    )
}
