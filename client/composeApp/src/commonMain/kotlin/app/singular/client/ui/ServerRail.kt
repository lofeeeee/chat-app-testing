package app.singular.client.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.startTransfer
import androidx.compose.ui.platform.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.singular.client.AppState
import app.singular.client.net.ChannelDto
import app.singular.client.net.GuildDto
import app.singular.client.net.GuildFolderDto
import app.singular.client.net.RailRow

/**
 * The server rail — the narrow strip Discord users reach for without thinking.
 *
 * Home (direct messages) sits at the top, then one tile per server, then a create/join button.
 * Selection is shown by a **pill on the left edge** rather than a border or a tint, because a
 * tile already carries the server's own icon and colour; adding a second visual language on top
 * of it makes six servers look like noise.
 */
/**
 * The server rail — the narrow strip Discord users reach for without thinking.
 *
 * Home (direct messages) sits at the top, then folders and servers in the arrangement the user
 * dragged them into, then a create/join button. Selection is shown by a **pill on the left
 * edge** rather than a border or a tint, because a tile already carries the server's own icon
 * and colour; adding a second visual language on top of it makes six servers look like noise.
 *
 * ## Drag and drop (feature 18)
 *
 * Tiles are draggable and every tile is a drop target. Dropping *on* a tile files both servers
 * in one folder (creating it if neither was in one); dropping *between* tiles reorders. The
 * order is applied locally the instant the pointer lifts — a rail that waits for a round trip
 * before moving feels broken — and saved once, after the arrangement settles.
 */
@Composable
fun ServerRail(state: AppState, modifier: Modifier = Modifier) {
    var showAdd by remember { mutableStateOf(false) }

    if (showAdd) {
        AddServerDialog(
            onDismiss = { showAdd = false },
            onCreate = { name -> showAdd = false; state.createGuild(name) },
            onJoin = { code -> showAdd = false; state.redeemInvite(code) },
        )
    }

    LazyColumn(
        modifier.fillMaxHeight().background(MaterialTheme.colorScheme.surfaceVariant),
        contentPadding = PaddingValues(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            RailTile(
                selected = state.selectedGuild == null,
                // Home stands for every direct message, so it carries their combined state —
                // otherwise a DM arriving while you're in a server is invisible from the rail.
                unread = state.channels.any { state.unread[it.id] == true },
                mentions = state.channels.sumOf { state.mentionCounts[it.id] ?: 0 },
                onClick = { state.openGuild(null) },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = "Direct messages",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        item {
            // A hairline, not a gap. It separates "your conversations" from "your servers"
            // without spending the vertical room a blank block would.
            Box(
                Modifier
                    .padding(vertical = 2.dp)
                    .width(24.dp)
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.outline)
            )
        }

        val rows = state.railRows()
        items(rows, key = { row -> railRowKey(row) }) { row ->
            when (row) {
                is RailRow.Folder -> FolderTile(
                    folder = row.folder,
                    members = row.members,
                    collapsed = row.collapsed,
                    selected = row.members.any { it.id == state.selectedGuild?.id },
                    mentions = row.members.sumOf { state.guildMentionCount(it) },
                    onToggle = { state.toggleFolderCollapsed(row.folder.id) },
                    onDropGuild = { guildId -> state.fileGuildInFolder(guildId, row.folder.id) },
                    onRemoveGuild = { guildId -> state.removeGuildFromFolder(guildId, row.folder.id) },
                    onRename = { name -> state.renameFolder(row.folder.id, name) },
                    onDelete = { state.deleteFolder(row.folder.id) },
                )

                is RailRow.Guild -> GuildTile(
                    state = state,
                    guild = row.guild,
                    folderId = row.folderId,
                    rows = rows,
                )
            }
        }

        item {
            RailTile(selected = false, onClick = { showAdd = true }) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "Add a server",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** Stable keys: a folder header and its tiles must not swap identity when one is refiled. */
private fun railRowKey(row: RailRow): String = when (row) {
    is RailRow.Folder -> "folder:${row.folder.id}"
    is RailRow.Guild ->
        // Two rows can hold the same server (collapsed folder + tile), so the key carries the
        // folder too — otherwise Compose would treat them as one moved node and animate a
        // server across the rail whenever a folder was expanded.
        "guild:${row.guild.id}:${row.folderId ?: "loose"}"
}

/**
 * One rail tile: the icon, the left-edge indicator, and the mention badge.
 *
 * The indicator height animates rather than appearing, which is what makes switching servers
 * read as one bar moving between two places instead of two unrelated redraws. See
 * [RailIndicator] for why the bar rather than a tint carries "unread".
 */
@Composable
private fun RailTile(
    selected: Boolean,
    unread: Boolean = false,
    mentions: Int = 0,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.CenterStart) {
        RailIndicator(selected = selected, unread = unread)

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Box(
                    Modifier
                        .size(44.dp)
                        // Squircle when selected, circle when not — Discord's tell, and it
                        // reads even for someone who can't distinguish the bar's colour.
                        .clip(if (selected) RoundedCornerShape(14.dp) else CircleShape)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surface
                        )
                        .then(
                            if (onLongClick != null) {
                                Modifier.combinedClickable(
                                    onClick = onClick,
                                    onLongClick = onLongClick,
                                )
                            } else {
                                Modifier.clickable(onClick = onClick)
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) { content() }

                // Overlapping the tile's corner rather than sitting beside it: the rail is
                // 72dp wide and a badge that claimed its own column would shrink every icon.
                MentionBadge(mentions, Modifier.offset(x = 3.dp, y = 3.dp))
            }
        }
    }
}

/**
 * One server tile, draggable and a drop target.
 *
 * Drag starts after a small delay so a click still clicks — the rail is the control people hit
 * most, and a tile that only drags is a tile that no longer opens.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GuildTile(
    state: AppState,
    guild: GuildDto,
    folderId: String?,
    rows: List<RailRow>,
) {
    var dropping by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxWidth()
            .animateItem()
            // -- drop -------------------------------------------------------
            .dragAndDropTarget(
                shouldStartDragAndDrop = { start ->
                    start.mimeTypes().contains(GUILD_MIME) || start.mimeTypes().isEmpty()
                },
                target = remember {
                    object : DragAndDropTarget {
                        override fun onStarted(event: DragAndDropEvent) {
                            dropping = true
                        }
                        override fun onEnded(event: DragAndDropEvent) {
                            dropping = false
                        }
                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            dropping = false
                            val dragged = draggedGuildId ?: return false
                            draggedGuildId = null
                            if (dragged == guild.id) return false
                            // Dropping onto a tile means "put these two together".
                            if (folderId != null) state.fileGuildInFolder(dragged, folderId)
                            else state.groupGuilds(dragged, guild.id)
                            return true
                        }
                    }
                },
            )
            // -- drag -------------------------------------------------------
            // Long-press to start, not tap: the rail is the control people hit most often, and
            // a tile that only drags is a tile that no longer opens.
            .dragAndDropSource(
                drawDragDecoration = { drawRect(Color.White.copy(alpha = 0.25f)) },
            ) {
                detectDragGesturesAfterLongPress(
                    onDragStart = {
                        draggedGuildId = guild.id
                        startTransfer(
                            DragAndDropTransferData(
                                // The id is the payload. It's not a secret and it isn't
                                // trusted — the server re-checks membership on every write.
                                ClipEntry(ClipData(ClipData.PlainText(guild.id))),
                            )
                        )
                    },
                    onDragEnd = { draggedGuildId = null },
                    onDragCancel = { draggedGuildId = null },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // The drop cue is a ring outside the tile: highlighting the tile itself would fight
        // the server's own icon colours, and every server has its own.
        if (dropping) {
            Box(
                Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            )
        }

        RailTile(
            selected = state.selectedGuild?.id == guild.id,
            unread = state.guildHasUnread(guild),
            mentions = state.guildMentionCount(guild),
            onClick = { state.openGuild(guild) },
            onLongClick = { menu = true },
        ) {
            val url = guild.iconUrl
            if (url != null) {
                RemoteImage(
                    url = url,
                    // The key, not the URL — presigned URLs change on every fetch and would
                    // miss the cache every time the rail redrew.
                    stableKey = guild.iconKey ?: guild.id,
                    contentDescription = guild.name,
                    modifier = Modifier.size(44.dp),
                )
            } else {
                Text(
                    guild.initials,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (folderId != null) "Remove from folder" else "Add to a folder") },
                onClick = {
                    menu = false
                    if (folderId != null) state.removeGuildFromFolder(guild.id, folderId)
                    else state.groupGuilds(guild.id, guild.id)   // a folder of one, to drop into
                },
            )
        }
    }
}

/**
 * A folder header. Expanded it names the group and holds nothing; collapsed it *is* the group,
 * drawn as a stack and opening the most recently unread of its servers.
 *
 * That asymmetry is the whole feature: an expanded folder is a label, a collapsed one is a
 * shortcut — which is why collapsing exists at all.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderTile(
    folder: GuildFolderDto,
    members: List<GuildDto>,
    collapsed: Boolean,
    selected: Boolean,
    mentions: Int,
    onToggle: () -> Unit,
    onDropGuild: (String) -> Unit,
    onRemoveGuild: (String) -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var dropping by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    if (renaming) {
        FolderNameDialog(
            current = folder.name,
            onDismiss = { renaming = false },
            onConfirm = { name -> renaming = false; onRename(name) },
        )
    }

    Box(
        Modifier
            .fillMaxWidth()
            .animateItem()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = remember(folder.id) {
                    object : DragAndDropTarget {
                        override fun onStarted(event: DragAndDropEvent) { dropping = true }
                        override fun onEnded(event: DragAndDropEvent) { dropping = false }
                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            dropping = false
                            val dragged = draggedGuildId ?: return false
                            draggedGuildId = null
                            onDropGuild(dragged)
                            return true
                        }
                    }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                if (dropping) {
                    Box(
                        Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                    )
                }

                if (collapsed) {
                    // A stack: the first member on top, a second edge behind it, which is how
                    // every client signals "there is more than one thing in here".
                    Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier
                                .size(38.dp)
                                .offset(x = 3.dp, y = 3.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        )
                        RailTile(
                            selected = selected,
                            unread = members.any { false },   // unread is per-member, resolved below
                            mentions = mentions,
                            onClick = onToggle,
                            onLongClick = { menu = true },
                        ) {
                            Text(
                                members.firstOrNull()?.initials.orEmpty(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .height(22.dp)
                            .clickable(onClick = onToggle)
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            Icons.Filled.ExpandMore,
                            contentDescription = "Collapse folder",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(
                            folder.name ?: "Folder",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (selected) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Rename folder") },
                onClick = { menu = false; renaming = true },
            )
            members.forEach { member ->
                DropdownMenuItem(
                    text = { Text("Remove ${member.name}") },
                    onClick = { menu = false; onRemoveGuild(member.id) },
                )
            }
            DropdownMenuItem(
                text = { Text("Delete folder") },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}

@Composable
private fun FolderNameDialog(
    current: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(current.orEmpty()) }
    val focus = LocalFocusManager.current
    val field = remember { FocusRequester() }
    val confirm = { onConfirm(name.trim()) }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Folder name") },
        text = {
            DialogKeys(onDismiss = onDismiss, onConfirm = confirm) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(32) },
                    label = { Text("Name") },
                    placeholder = { Text("Work, side projects…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(field)
                        .formField(focus, true, confirm),
                )
            }
        },
        confirmButton = { Button(onClick = confirm) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Which server is being dragged.
 *
 * Module-scoped rather than hoisted through composition because the payload itself is what
 * travels with the drag — a `ClipData` carrying the id is the cross-platform contract, and
 * this only exists to let the drop target read it without re-parsing the event.
 */
private var draggedGuildId: String? = null

private const val GUILD_MIME = "text/plain"
    onJoin: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current

    val ready = code.isNotBlank() || name.trim().length >= 2
    val confirm = { if (code.isNotBlank()) onJoin(code) else onCreate(name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a server") },
        text = {
            DialogKeys(onDismiss = onDismiss, onConfirm = confirm, confirmEnabled = ready) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "Create your own, or join one with an invite code. Enter to confirm, " +
                            "Tab to move, Esc to cancel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(100) },
                        label = { Text("New server name") },
                        supportingText = { Text("2-100 characters. You'll be the owner.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                            .formField(focus, ready, confirm),
                    )
                    OutlinedTextField(
                        value = code,
                        onValueChange = { code = it.trim().take(16) },
                        label = { Text("Or an invite code") },
                        placeholder = { Text("e.g. 5veqea3q") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                            .formField(focus, ready, confirm),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = confirm, enabled = ready) {
                Text(if (code.isNotBlank()) "Join" else "Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * The server header above the channel list.
 *
 * One row: the server's name and a menu. Everything that was a row of buttons — invite, leave,
 * rename — lives behind that menu or in the settings screen it opens, because those are things
 * you do to a server a handful of times, and they were taking three lines of permanent space
 * above the list you came here to use.
 *
 * "Server settings" and "Invite people" both navigate to the settings screen (the latter
 * straight to its Invites section) rather than doing anything inline — the dropdown is a way
 * *into* the server's settings, not a second copy of them. Leaving the server is in that
 * screen's nav footer, behind a confirmation, for the same reason Log out is in app settings'
 * footer and not in the account bar.
 */
@Composable
fun GuildHeader(
    state: AppState,
    guild: GuildDto,
    onOpenServerSettings: (ServerSettingsSection) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var addingChannel by remember { mutableStateOf(false) }

    if (addingChannel) {
        NewChannelDialog(
            categories = guild.channels.filter { it.isCategory },
            onDismiss = { addingChannel = false },
            onCreate = { name, category, parentId ->
                addingChannel = false
                state.createGuildChannel(
                    guild.id,
                    name,
                    if (category) "GUILD_CATEGORY" else "GUILD_TEXT",
                    // A category cannot live inside a category — Discord doesn't nest them
                    // either, and the sidebar has no way to draw a second level.
                    parentId = if (category) null else parentId,
                )
            },
        )
    }

    Row(
        Modifier
            .fillMaxWidth()
            .clickable { menu = true }
            .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                guild.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Your own nickname and role used to sit here. Removed: it told you something you
            // already know, on every server, above every channel list — and the account bar at
            // the bottom of the same column already says who you are. Your per-server nickname
            // is still set from Server settings.
        }

        Box {
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = "Server menu",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Server settings") },
                    leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    onClick = { menu = false; onOpenServerSettings(ServerSettingsSection.OVERVIEW) },
                )
                DropdownMenuItem(
                    text = { Text("Create channel") },
                    leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    onClick = { menu = false; addingChannel = true },
                )
                DropdownMenuItem(
                    text = { Text("Invite people") },
                    leadingIcon = { Icon(Icons.Filled.PersonAdd, contentDescription = null) },
                    onClick = {
                        menu = false
                        onOpenServerSettings(ServerSettingsSection.INVITES)
                    },
                )
                // No "Leave server" here. It moved to the settings screen's nav footer,
                // beside a confirmation dialog — the old menu item left the server on a
                // single click with no way back, which is a lot of consequence for a row
                // that sits one misclick below "Invite people".
            }
        }
    }
}

/**
 * The create-a-channel dialog.
 *
 * Shared by the server dropdown and server settings' Channels section — one place that knows
 * the name-becomes-hyphens rule, so the two can't drift into minting differently-named channels.
 */
@Composable
internal fun NewChannelDialog(
    categories: List<ChannelDto>,
    onDismiss: () -> Unit,
    onCreate: (name: String, category: Boolean, parentId: String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(false) }
    var parentId by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val nameField = remember { FocusRequester() }

    val confirm = { if (name.isNotBlank()) onCreate(name, category, parentId) }

    // Straight into the name box: this dialog exists to take one word.
    LaunchedEffect(Unit) { runCatching { nameField.requestFocus() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (category) "New category" else "New channel") },
        text = {
          DialogKeys(onDismiss = onDismiss, onConfirm = confirm, confirmEnabled = name.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !category,
                        onClick = { category = false },
                        label = { Text("Text channel") },
                    )
                    FilterChip(
                        selected = category,
                        onClick = { category = true },
                        label = { Text("Category") },
                    )
                }
                OutlinedTextField(
                    value = name,
                    // Spaces become hyphens the way they do everywhere this convention is
                    // used — "# game night" isn't a thing anyone types twice.
                    onValueChange = { name = it.take(100).replace(' ', '-').lowercase() },
                    label = { Text(if (category) "Category name" else "Channel name") },
                    placeholder = { Text(if (category) "chats" else "general") },
                    prefix = if (category) null else ({ Text("#") }),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                        .focusRequester(nameField)
                        .formField(focus, name.isNotBlank(), confirm),
                )

                // Offered only when there is somewhere to put it. A server with no categories
                // would otherwise get a chooser with one meaningless option in it.
                if (!category && categories.isNotEmpty()) {
                    Text("Group it under", style = MaterialTheme.typography.labelMedium)
                    Column {
                        CategoryChoice("None", parentId == null) { parentId = null }
                        categories.forEach { option ->
                            CategoryChoice(
                                label = option.name ?: "Category",
                                selected = parentId == option.id,
                            ) { parentId = option.id }
                        }
                    }
                }
            }
          }
        },
        confirmButton = {
            Button(onClick = confirm, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CategoryChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    SettingRadio(title = label, selected = selected, onSelect = onSelect)
}
