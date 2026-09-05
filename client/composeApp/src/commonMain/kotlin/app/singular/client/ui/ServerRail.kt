package app.singular.client.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * The server rail — the narrow strip Discord users reach for without thinking.
 *
 * Home (direct messages) sits at the top, then one tile per server, then a create/join button.
 * Selection is shown by a **pill on the left edge** rather than a border or a tint, because a
 * tile already carries the server's own icon and colour; adding a second visual language on top
 * of it makes six servers look like noise.
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

        items(state.guilds, key = { it.id }) { guild ->
            RailTile(
                selected = state.selectedGuild?.id == guild.id,
                onClick = { state.openGuild(guild) },
            ) {
                val url = guild.iconUrl
                if (url != null) {
                    RemoteImage(
                        url = url,
                        // The key, not the URL — presigned URLs change on every fetch and
                        // would miss the cache every time the rail redrew.
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

/**
 * One rail tile plus its selection pill.
 *
 * The pill animates its height rather than appearing, which is what makes switching servers
 * read as movement between two places instead of two unrelated redraws.
 */
@Composable
private fun RailTile(
    selected: Boolean,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    val pillHeight by animateDpAsState(
        targetValue = if (selected) 28.dp else 0.dp,
        label = "rail-pill",
    )

    Box(Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.CenterStart) {
        Box(
            Modifier
                .padding(start = 2.dp)
                .width(3.dp)
                .height(pillHeight)
                .clip(RoundedCornerShape(topEnd = 3.dp, bottomEnd = 3.dp))
                .background(MaterialTheme.colorScheme.primary)
        )

        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(44.dp)
                    // Squircle when selected, circle when not — Discord's tell, and it reads
                    // even for someone who can't distinguish the pill's colour.
                    .clip(if (selected) RoundedCornerShape(14.dp) else CircleShape)
                    .background(
                        if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surface
                    )
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center,
            ) { content() }
        }
    }
}

@Composable
private fun AddServerDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
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
            guild.me?.let { me ->
                Text(
                    // The per-server nickname if there is one — that is the whole point of it.
                    me.displayName +
                        (me.roles.firstOrNull { !it.isDefault }?.let { " · ${it.name}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
