package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.singular.client.AppState
import app.singular.client.net.ChannelDto
import app.singular.client.net.GuildDto
import app.singular.client.net.GuildMemberDto
import app.singular.client.net.InviteDto
import app.singular.client.net.Permission
import app.singular.client.net.RoleDto
import app.singular.client.net.myPermissionSet

/**
 * Server settings — app settings' layout, at a server's scope.
 *
 * Everything here changes the server for everybody in it, which is why it is a deliberate
 * screen rather than inline controls in the sidebar: renaming a server should take a decision,
 * not a mis-click on a header. The old version was one dialog with everything in it, which
 * worked at four controls and got worse from there; it now shares the nav-plus-cards layout
 * with [SettingsScreen], so the two read as the same idea at two scopes.
 *
 * Sections are gated on [GuildDto.myPermissionSet] rather than ownership: the server enforces
 * these same flags, so an owner check would both hide controls from moderators who hold
 * MANAGE_GUILD and offer controls to nobody who can actually use them.
 */
@Composable
fun ServerSettingsScreen(
    state: AppState,
    guild: GuildDto,
    initialSection: ServerSettingsSection = ServerSettingsSection.OVERVIEW,
    onClose: () -> Unit,
) {
    val can = guild.myPermissionSet
    val visible = visibleSections(can)

    // Re-keyed on the guild so switching servers in the rail behind this screen (if it ever
    // becomes reachable without closing) cannot leave another server's section showing.
    //
    // The initial section is clamped to the ones this viewer gets: "Invite people" opens at
    // Invites, but if the viewer somehow lacks the flag that section isn't in their nav, and
    // landing on an entry you can't click back to is a pane with no way out but Back.
    var section by remember(guild.id) {
        mutableStateOf(initialSection.takeIf { it in visible } ?: ServerSettingsSection.OVERVIEW)
    }
    var confirmLeave by remember(guild.id) { mutableStateOf(false) }
    var addingChannel by remember(guild.id) { mutableStateOf(false) }
    var creatingRole by remember(guild.id) { mutableStateOf(false) }

    // Members and invites load on entry rather than on section switch: both are one request,
    // and preloading means clicking through the nav never shows a blank pane first.
    LaunchedEffect(guild.id) {
        state.loadGuildMembers(guild.id)
        if (can.allows(Permission.CREATE_INVITE)) state.loadInvites(guild.id)
    }

    if (confirmLeave) {
        LeaveServerDialog(
            guildName = guild.name,
            isOwner = state.currentUser?.id == guild.ownerId,
            onDismiss = { confirmLeave = false },
            onConfirm = { confirmLeave = false; state.leaveGuild(guild.id); onClose() },
        )
    }
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
                    parentId = if (category) null else parentId,
                )
            },
        )
    }
    if (creatingRole) {
        NewRoleDialog(
            onDismiss = { creatingRole = false },
            onCreate = { name -> creatingRole = false; state.createRole(guild.id, name) },
        )
    }

    Row(Modifier.fillMaxSize()) {
        SettingsNav(
            title = guild.name,
            items = visible.map { SettingsNavItem(it, it.title, it.blurb) },
            selected = section,
            onPick = { section = it },
            onClose = onClose,
            footer = { ServerLeaveButton { confirmLeave = true } },
        )
        VerticalDivider()

        SettingsPane(section.title) {
            when (section) {
                ServerSettingsSection.OVERVIEW -> OverviewSection(state, guild, can)
                ServerSettingsSection.MEMBERS -> MembersSection(state, guild, can)
                ServerSettingsSection.ROLES -> RolesSection(state, guild, can, onNewRole = { creatingRole = true })
                ServerSettingsSection.CHANNELS -> ChannelsSection(guild, can, onAddChannel = { addingChannel = true })
                ServerSettingsSection.INVITES -> InvitesSection(state, guild, can)
                ServerSettingsSection.MY_PROFILE -> MyProfileSection(state, guild, can)
            }

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The server settings sections, in the order they're listed.
 *
 * Ordered by how often they're visited rather than by importance: Overview and My profile are
 * the everyday ones, and administration — Members, Roles, Channels, Invites — follows, so the
 * nav a plain member sees is already the top of the list.
 */
enum class ServerSettingsSection(val title: String, val blurb: String) {
    OVERVIEW("Overview", "Name, icon, description"),
    MY_PROFILE("My profile", "Your nickname here"),
    MEMBERS("Members", "Everyone in this server"),
    ROLES("Roles", "Names, colours, display"),
    CHANNELS("Channels", "Categories and channels"),
    INVITES("Invites", "Join codes"),
}

/**
 * The sections this viewer gets in the nav. My profile and Overview are for everyone;
 * the administrative ones appear only with the flag that makes them usable, because a nav
 * entry that opens to "you can't do anything here" is a dead end wearing a label.
 */
private fun visibleSections(can: app.singular.client.net.GuildPermissions) =
    buildList {
        add(ServerSettingsSection.OVERVIEW)
        add(ServerSettingsSection.MY_PROFILE)
        if (can.allows(Permission.KICK_MEMBERS) || can.allows(Permission.MANAGE_ROLES)) {
            add(ServerSettingsSection.MEMBERS)
        }
        if (can.allows(Permission.MANAGE_ROLES)) add(ServerSettingsSection.ROLES)
        if (can.allows(Permission.MANAGE_CHANNELS)) add(ServerSettingsSection.CHANNELS)
        if (can.allows(Permission.CREATE_INVITE)) add(ServerSettingsSection.INVITES)
    }

// ---------------------------------------------------------------------------
// Overview
// ---------------------------------------------------------------------------

@Composable
private fun OverviewSection(
    state: AppState,
    guild: GuildDto,
    can: app.singular.client.net.GuildPermissions,
) {
    val editable = can.allows(Permission.MANAGE_GUILD)

    // Staged locally and saved on one button, so a half-typed name never reaches the other
    // members. Re-keyed on the live values so a save elsewhere (or a reload) re-seeds them.
    var name by remember(guild.id, guild.name) { mutableStateOf(guild.name) }
    var description by remember(guild.id, guild.description) {
        mutableStateOf(guild.description.orEmpty())
    }
    val dirty = name.trim() != guild.name || description.trim() != guild.description.orEmpty()

    SettingsCard {
        SettingsCardTitle("Server icon")
        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarPicker(
                size = 64.dp,
                enabled = editable && state.uploadProgress == null,
                onPick = { deliver -> state.pickImage(deliver) },
                onUpload = { cropped -> state.uploadGuildIcon(guild.id, cropped) },
                title = "Server icon",
                uploadLabel = "Upload server icon",
            ) { drawAt ->
                GuildIconOrInitials(guild, drawAt)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    if (editable) "Click the icon to view or replace it."
                    else "Only members who can manage this server may change it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.uploadProgress?.let { progress ->
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        }
    }

    SettingsCard {
        SettingsCardTitle("Identity")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(100) },
            label = { Text("Server name") },
            singleLine = true,
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it.take(300) },
            label = { Text("Description") },
            placeholder = { Text("What this server is for") },
            enabled = editable,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { state.updateGuild(guild.id, name = name, description = description) },
            enabled = editable && dirty && name.trim().length >= 2 && !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Save changes") }
    }

    SettingsCard {
        SettingsCardTitle("About")
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Channels", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${guild.channels.count { it.type == "GUILD_TEXT" }}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Roles", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                "${guild.roles.size}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Members
// ---------------------------------------------------------------------------

@Composable
private fun MembersSection(
    state: AppState,
    guild: GuildDto,
    can: app.singular.client.net.GuildPermissions,
) {
    val me = state.currentUser?.id
    val canKick = can.allows(Permission.KICK_MEMBERS)
    val canManageRoles = can.allows(Permission.MANAGE_ROLES)

    SettingsCard {
        SettingsCardTitle("Members")
        Text(
            "${state.guildMembers.size} people, each shown with the roles they hold here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.guildMembers.forEach { member ->
            MemberRow(
                member = member,
                guild = guild,
                isYou = member.user.id == me,
                isOwner = member.user.id == guild.ownerId,
                canKick = canKick && member.user.id != me && member.user.id != guild.ownerId,
                canManageRoles = canManageRoles && member.user.id != guild.ownerId,
                onKick = { state.kickMember(guild.id, member.user.id) },
                onToggleRole = { roleId, assigned ->
                    state.setMemberRole(guild.id, member.user.id, roleId, assigned)
                },
            )
        }
    }
}

@Composable
private fun MemberRow(
    member: GuildMemberDto,
    guild: GuildDto,
    isYou: Boolean,
    isOwner: Boolean,
    canKick: Boolean,
    canManageRoles: Boolean,
    onKick: () -> Unit,
    onToggleRole: (roleId: String, assigned: Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Avatar(member.user, 36, member.displayName)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    member.displayName + if (isYou) " (you)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isOwner) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    member.user.handle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (isOwner) {
                Text(
                    "Owner",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (canManageRoles && guild.roles.any { !it.isDefault }) {
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide roles" else "Roles")
                }
            }
            if (canKick) {
                TextButton(onClick = onKick) { Text("Kick", color = MaterialTheme.colorScheme.error) }
            }
        }

        if (expanded) {
            // The assignable roles, as one toggle each. A full member×role matrix would be a
            // wall of switches; per-member expansion keeps it to the one person you're on.
            guild.roles.filter { !it.isDefault }.forEach { role ->
                SettingToggle(
                    title = role.name,
                    checked = member.roles.any { it.id == role.id },
                    onCheckedChange = { onToggleRole(role.id, it) },
                    modifier = Modifier.padding(start = 46.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Roles
// ---------------------------------------------------------------------------

@Composable
private fun RolesSection(
    state: AppState,
    guild: GuildDto,
    can: app.singular.client.net.GuildPermissions,
    onNewRole: () -> Unit,
) {
    val editable = can.allows(Permission.MANAGE_ROLES)
    var selected by remember(guild.id) { mutableStateOf<RoleDto?>(null) }
    // Re-selected by id, because loadGuilds() replaces the role objects after every save and
    // holding the DTO would freeze the editor on the pre-save copy.
    val current = selected?.let { sel -> guild.roles.firstOrNull { it.id == sel.id } }

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SettingsCardTitle("Roles")
                Text(
                    "Higher roles outrank lower ones. @everyone is everyone's baseline.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editable) {
                TextButton(onClick = onNewRole, enabled = !state.busy) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New role")
                }
            }
        }

        // Highest position first — the same order the hierarchy is described in.
        guild.roles.sortedByDescending { it.position }.forEach { role ->
            val active = current?.id == role.id
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                    )
                    .clickable(enabled = editable && !role.isDefault) { selected = role }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoleColourDot(role)
                Spacer(Modifier.width(10.dp))
                Text(
                    role.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (role.isDefault) FontWeight.Normal else FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                if (role.isDefault) {
                    Text(
                        "everyone",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    // The editor appears below the list rather than in place: selecting a role to compare it
    // against another is half of what the list is for, and an in-place swap would erase it.
    current?.let { role ->
        SettingsCard {
            RoleEditor(
                role = role,
                editable = editable,
                busy = state.busy,
                onSave = { name, color, hoist, mentionable ->
                    state.updateRole(role.id, name, color, hoist, mentionable)
                },
                onDelete = {
                    state.deleteRole(role.id)
                    selected = null
                },
            )
        }
    }
}

@Composable
private fun RoleEditor(
    role: RoleDto,
    editable: Boolean,
    busy: Boolean,
    onSave: (name: String?, color: Int?, hoist: Boolean?, mentionable: Boolean?) -> Unit,
    onDelete: () -> Unit,
) {
    var name by remember(role.id, role.name) { mutableStateOf(role.name) }
    var hoist by remember(role.id, role.hoist) { mutableStateOf(role.hoist) }
    var mentionable by remember(role.id, role.mentionable) { mutableStateOf(role.mentionable) }
    val dirty = name.trim() != role.name || hoist != role.hoist || mentionable != role.mentionable

    SettingsCardTitle("Edit ${role.name}")
    OutlinedTextField(
        value = name,
        onValueChange = { name = it.take(64) },
        label = { Text("Role name") },
        singleLine = true,
        enabled = editable,
        modifier = Modifier.fillMaxWidth(),
    )
    SettingToggle(
        title = "Show separately",
        description = "Members with this role get their own section in the member list.",
        checked = hoist,
        onCheckedChange = { hoist = it },
        enabled = editable,
    )
    SettingToggle(
        title = "Mentionable",
        description = "Anyone can @mention this role, which notifies everyone holding it.",
        checked = mentionable,
        onCheckedChange = { mentionable = it },
        enabled = editable,
    )

    if (editable) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onSave(name.trim().ifEmpty { null }, null, hoist, mentionable) },
                enabled = dirty && !busy,
            ) { Text("Save role") }
            // Last, and in the error colour: deleting a role is the one action on this card
            // that can't be undone by clicking Save again.
            TextButton(onClick = onDelete, enabled = !busy) {
                Text("Delete role", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun NewRoleDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New role") },
        text = {
            DialogKeys(onDismiss = onDismiss, onConfirm = { onCreate(name) }, confirmEnabled = name.trim().length >= 2) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(64) },
                    label = { Text("Role name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name) }, enabled = name.trim().length >= 2) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------------
// Channels
// ---------------------------------------------------------------------------

@Composable
private fun ChannelsSection(
    guild: GuildDto,
    can: app.singular.client.net.GuildPermissions,
    onAddChannel: () -> Unit,
) {
    val editable = can.allows(Permission.MANAGE_CHANNELS)
    val categories = guild.channels.filter { it.isCategory }.sortedBy { it.name }
    val text = guild.channels.filter { it.type == "GUILD_TEXT" }
    val grouped = text.groupBy { it.parentId }

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SettingsCardTitle("Channels")
                Text(
                    "There is no channel deletion yet — the server has no such mutation, and a " +
                        "settings screen that pretends otherwise would be a lie with a button.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (editable) {
                TextButton(onClick = onAddChannel) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New channel")
                }
            }
        }

        // The sidebar's grouping, restated: uncategorised channels first, then each category
        // with its children — so this list and the one beside the settings button agree.
        grouped[null]?.let { loose ->
            ChannelGroupLabel("Uncategorised", loose.size)
            loose.forEach { ChannelLine(it) }
        }
        categories.forEach { category ->
            ChannelGroupLabel(category.name ?: "Category", grouped[category.id].orEmpty().size)
            grouped[category.id].orEmpty().forEach { ChannelLine(it) }
        }
    }
}

@Composable
private fun ChannelGroupLabel(label: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            "$count",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChannelLine(channel: ChannelDto) {
    Text(
        "#" + channel.name.orEmpty(),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 4.dp).padding(vertical = 2.dp),
    )
}

// ---------------------------------------------------------------------------
// Invites
// ---------------------------------------------------------------------------

@Composable
private fun InvitesSection(
    state: AppState,
    guild: GuildDto,
    can: app.singular.client.net.GuildPermissions,
) {
    val canCreate = can.allows(Permission.CREATE_INVITE)
    val canManage = can.allows(Permission.MANAGE_INVITES)

    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SettingsCardTitle("Invite links")
                Text(
                    "Anyone with a code can join. Codes are minted immediately — a code you " +
                        "have to \"save\" to obtain would be a strange thing indeed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (canCreate) {
                TextButton(onClick = { state.createInvite(guild.id) }, enabled = !state.busy) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New code")
                }
            }
        }

        if (state.guildInvites.isEmpty()) {
            Text(
                "No invite codes yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            state.guildInvites.forEach { invite ->
                InviteRow(invite, canManage, onRevoke = { state.deleteInvite(invite.code) })
            }
        }
    }
}

@Composable
private fun InviteRow(invite: InviteDto, canRevoke: Boolean, onRevoke: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Codes are shown in full, never truncated. A partially visible invite code is
        // worthless — the entire purpose of the row is to be copied.
        Text(
            invite.code,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            invite.maxUses?.let { "${invite.uses}/$it uses" } ?: "${invite.uses} uses",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canRevoke) {
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onRevoke) {
                Text("Revoke", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// My profile
// ---------------------------------------------------------------------------

@Composable
private fun MyProfileSection(
    state: AppState,
    guild: GuildDto,
    can: app.singular.client.net.GuildPermissions,
) {
    val editable = can.allows(Permission.CHANGE_NICKNAME)
    var nickname by remember(guild.id, guild.me?.nickname) {
        mutableStateOf(guild.me?.nickname.orEmpty())
    }

    SettingsCard {
        SettingsCardTitle("Your nickname here")
        Text(
            "How you appear in this server only. Leave it empty to use your display name.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it.take(32) },
                placeholder = { Text(guild.me?.user?.label.orEmpty()) },
                singleLine = true,
                enabled = editable,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { state.setNickname(guild.id, nickname = nickname.trim().ifEmpty { null }) },
                enabled = editable && !state.busy,
            ) { Text("Set") }
        }
    }

    guild.me?.let { me ->
        if (me.roles.isNotEmpty()) {
            SettingsCard {
                SettingsCardTitle("Your roles")
                me.roles.sortedByDescending { it.position }.forEach { role ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RoleColourDot(role)
                        Spacer(Modifier.width(10.dp))
                        Text(role.name, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

/** The guild's icon, or its initials until it has one — [AvatarPicker]'s picture half. */
@Composable
private fun GuildIconOrInitials(guild: GuildDto, drawAt: androidx.compose.ui.unit.Dp = 64.dp) {
    val url = guild.iconUrl
    if (url != null) {
        RemoteImage(
            url = url,
            // Keyed on iconKey, not the URL: the URL is presigned and different on every
            // fetch, so caching against it would miss every single time.
            stableKey = guild.iconKey ?: guild.id,
            contentDescription = "${guild.name} icon",
            modifier = Modifier.size(drawAt).clip(CircleShape),
        )
    } else {
        // Initials rather than a generic placeholder glyph, so a server without an icon is
        // still distinguishable from the other servers without one. The type scales with the
        // circle so the 220dp dialog doesn't show a monogram sized for a 64dp tile.
        Text(
            guild.initials,
            style = MaterialTheme.typography.titleMedium,
            fontSize = (drawAt.value * 0.32f).sp,
            lineHeight = (drawAt.value * 0.38f).sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun RoleColourDot(role: RoleDto) {
    Box(
        Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(role.color?.let { Color(it) } ?: MaterialTheme.colorScheme.outline),
    )
}

/**
 * The nav's last row, alone below its own divider and in the error colour — the same slot
 * "Log out" occupies in app settings, for the same reason: it is the one item here that ends
 * your membership rather than changing a setting.
 */
@Composable
private fun ServerLeaveButton(onLeave: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onLeave)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Logout,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            "Leave server",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun LeaveServerDialog(
    guildName: String,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Leave $guildName?") },
        text = {
            // Escape cancels. Enter is not bound — leaving ends your membership, and that
            // should take a click rather than a keystroke you meant for the field behind it.
            DialogKeys(onDismiss = onDismiss) {
                Text(
                    if (isOwner) {
                        "You own this server, and the server refuses to let its owner leave " +
                            "without transferring ownership first — otherwise it would be " +
                            "orphaned with nobody able to administer it."
                    } else {
                        "You'll stop being a member and won't be able to rejoin without a new " +
                            "invite code."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            if (isOwner) {
                // The button is disabled rather than hidden: hiding it would leave a dialog
                // whose only action is "don't", which reads as broken rather than refused.
                Button(onClick = {}, enabled = false) { Text("Leave server") }
            } else {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) { Text("Leave server") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stay") } },
    )
}
