package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.singular.client.AppState
import app.singular.client.platform.notificationsAvailable

/**
 * Settings.
 *
 * Chat layout lives here rather than in the conversation header, where it started. It's a
 * preference you set once and forget, not an action you take on a particular chat â€” putting it
 * beside Mute and Block implied it was per-conversation, which it never was.
 */
@Composable
fun SettingsScreen(state: AppState, onClose: () -> Unit) {
    var section by remember { mutableStateOf(SettingsSection.ACCOUNT) }
    var confirmSignOut by remember { mutableStateOf(false) }

    if (confirmSignOut) {
        SignOutDialog(
            onDismiss = { confirmSignOut = false },
            onConfirm = { confirmSignOut = false; state.signOut() },
        )
    }

    Row(Modifier.fillMaxSize()) {
        SettingsNav(
            title = "Settings",
            items = SettingsSection.entries.map { SettingsNavItem(it, it.title, it.blurb) },
            selected = section,
            onPick = { section = it },
            onClose = onClose,
            footer = { SettingsSignOutButton { confirmSignOut = true } },
        )
        VerticalDivider()

        SettingsPane(section.title) {
            when (section) {
                SettingsSection.ACCOUNT -> AccountSection(state)
                SettingsSection.APPEARANCE -> AppearanceSection(state)
                SettingsSection.PRIVACY -> PrivacySection(state)
                SettingsSection.NOTIFICATIONS -> NotificationsSection(state)
                SettingsSection.ABOUT -> AboutSection()
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
 * The settings sections, in the order they're listed.
 *
 * Grouped by what you came to change rather than by which screen the control used to live on.
 * The previous version was one scroll with everything in it, which works at five controls and
 * stops working at fifteen — you end up scrolling past the theme picker every time you want to
 * edit your bio.
 */
enum class SettingsSection(val title: String, val blurb: String) {
    ACCOUNT("Account", "Name, handle, picture"),
    APPEARANCE("Appearance", "Theme and chat layout"),
    PRIVACY("Privacy", "Blocked and muted"),
    NOTIFICATIONS("Notifications", "Alerts on this device"),
    ABOUT("About", "Version and shortcuts"),
}

/**
 * The nav's last row, alone below its own divider and in the error colour.
 *
 * Sign out is the one item in settings that ends what you were doing, so it should not sit in
 * the same list as "pick a theme" — the same rule the server settings screen follows for
 * leaving a server.
 */
@Composable
private fun SettingsSignOutButton(onSignOut: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onSignOut)
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
            "Log out",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun SignOutDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Log out?") },
        text = {
            // Escape cancels. Enter is not bound — signing out ends the session on this
            // device, and that should take a click rather than a keystroke you meant for the
            // field behind the dialog.
            DialogKeys(onDismiss = onDismiss) {
                Text(
                    "This device will be signed out and its session revoked. Your other " +
                        "devices stay signed in.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
            ) { Text("Log out") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Stay signed in") } },
    )
}

// ---------------------------------------------------------------------------
// Appearance â€” layout + theme (feature 16)
// ---------------------------------------------------------------------------

@Composable
private fun AppearanceSection(state: AppState) {
    SettingsCard {
        Text("Appearance", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        Text("Chat layout", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LayoutOption(
                title = "Bubbles",
                description = "Yours on the right, time inside the bubble.",
                selected = state.chatLayout == "BUBBLES",
                onClick = { state.setLayout("BUBBLES") },
                modifier = Modifier.weight(1f),
            ) { BubblePreview() }
            LayoutOption(
                title = "Compact",
                description = "Denser, everything left-aligned.",
                selected = state.chatLayout == "COMPACT",
                onClick = { state.setLayout("COMPACT") },
                modifier = Modifier.weight(1f),
            ) { CompactPreview() }
        }

        HorizontalDivider()

        Text("Theme", style = MaterialTheme.typography.labelLarge)
        Text(
            "Each theme is a finished palette, tuned for dark and light. " +
                "Your choice follows your account to every device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PresetGrid(
            selected = state.themePreset ?: Presets.default.id,
            onPick = { state.chooseThemePreset(it) },
        )

        HorizontalDivider()

        SettingToggle(
            title = "Dark theme",
            description = if (state.themeDark == null) "Following your system setting"
                          else "Set manually",
            checked = state.themeDark ?: true,
            onCheckedChange = { state.setThemeDark(it) },
        )
    }
}

@Composable
private fun LayoutOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                shape = MaterialTheme.shapes.small,
            )
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // A miniature of the real thing. A radio button labelled "Compact" tells you nothing
        // about what you're choosing; a picture of it does.
        Box(Modifier.fillMaxWidth().height(64.dp)) { preview() }
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(
            description,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BubblePreview() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        MiniBubble(mine = false, width = 0.55f)
        MiniBubble(mine = true, width = 0.45f)
        MiniBubble(mine = true, width = 0.35f)
    }
}

@Composable
private fun MiniBubble(mine: Boolean, width: Float) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .fillMaxWidth(width)
                .height(13.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(
                    // raised, matching the real bubble in MessageList. This preview used
                    // `surface`, which is a different colour â€” a preview that doesn't match the
                    // thing it previews is worse than no preview.
                    if (mine) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
        )
    }
}

@Composable
private fun CompactPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Only the first row of a run carries an avatar â€” the same rule the real
                // layout uses, so the preview isn't lying about grouping.
                if (row == 0) {
                    Box(
                        Modifier.size(12.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                } else {
                    Spacer(Modifier.size(12.dp))
                }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .fillMaxWidth(if (row == 1) 0.8f else 0.62f)
                        .height(9.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(MaterialTheme.colorScheme.surface)
                )
            }
        }
    }
}

/**
 * The theme picker.
 *
 * A preset is shown as a strip of its own real colours â€” canvas, surface, raised, then the
 * accent â€” because that's what you're actually choosing. A swatch of the accent alone, which is
 * what the old picker showed, tells you nothing about the four surfaces that dominate the
 * screen; it's a paint chip for a room you can't see.
 *
 * Each strip renders in the mode currently applied, so the toggle above it previews what the
 * theme looks like right now rather than a fixed snapshot.
 */
@Composable
private fun PresetGrid(selected: String, onPick: (String) -> Unit) {
    val applied = LocalSingularColors.current
    // The strip shows the preset's own colours, not the applied theme's â€” the whole point is
    // comparison between presets. Dark is read off the applied theme so a user flipping the
    // dark toggle sees each preset in the mode they'd get.
    val dark = applied.canvas.luminance() < 0.5f

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Presets.all.forEach { preset ->
            PresetOption(preset, dark, selected = preset.id == selected) { onPick(preset.id) }
        }
    }
}

@Composable
private fun PresetOption(
    preset: ThemePreset,
    dark: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val n = preset.neutrals(dark)
    val a = preset.accent(dark)
    val canvas = composite(n.canvas, a.canvasTint)
    val scheme = MaterialTheme.colorScheme

    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) scheme.primary else scheme.outline,
                shape = MaterialTheme.shapes.medium,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The strip: canvas â†’ surface â†’ raised, with the accent drawn as a bubble on top so
        // it's seen in context rather than floating.
        Box(
            Modifier
                .size(width = 84.dp, height = 44.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(canvas)
                .padding(4.dp),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(
                    Modifier
                        .size(width = 22.dp, height = 30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(n.raised)
                )
                Box(
                    Modifier
                        .size(width = 22.dp, height = 30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(a.accent)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(preset.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                preset.blurb,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(scheme.primary)
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Profile (feature 13)
// ---------------------------------------------------------------------------

@Composable
private fun AccountSection(state: AppState) {
    val me = state.currentUser ?: return
    var displayName by remember(me.id) { mutableStateOf(me.displayName ?: me.username) }
    var bio by remember(me.id) { mutableStateOf(me.bio ?: "") }
    var pronouns by remember(me.id) { mutableStateOf(me.pronouns ?: "") }

    // Re-keyed on the handle so it refreshes after a rename — the server may hand back a
    // different number than the one that went in, and the field has to show what you got.
    var username by remember(me.handle) { mutableStateOf(me.username) }

    SettingsCard {
        Text("Profile picture", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            // The circle is the button. Clicking your own picture to change it is what
            // people try first, and a picture that ignores the click reads as broken —
            // so both this and the button beside it do the same thing.
            AvatarPicker(
                size = 84.dp,
                enabled = state.uploadProgress == null,
                onPick = { deliver -> state.pickImage(deliver) },
                onUpload = { cropped -> state.uploadAvatar(cropped) },
                title = "Profile picture",
                uploadLabel = "Upload profile picture",
            ) { drawAt ->
                UserAvatar(
                    user = me,
                    label = displayName.ifBlank { me.username },
                    size = drawAt.value.toInt(),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    displayName.ifBlank { me.username },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    me.handle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Click your picture to view it or upload a new one.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        state.uploadProgress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
        }
    }

    // -- Custom status --------------------------------------------------------

    // The emoji + text status shown beside your presence dot. The server has carried this
    // field since presence shipped; this editor is the first UI that writes it.
    var statusEmoji by remember(me.id) { mutableStateOf(me.presence?.customEmoji ?: "") }
    var statusText by remember(me.id) { mutableStateOf(me.presence?.customText ?: "") }
    var statusEmojiPicker by remember { mutableStateOf(false) }
    val statusRecents = rememberRecentEmoji()

    SettingsCard {
        Text("Custom status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Shown beside your presence dot, on your profile and in the sidebar. " +
                "Clear both fields to remove it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            // The emoji half. A preview button that opens the picker — same pattern as
            // AvatarPicker: the thing itself is the button.
            Box(
                Modifier
                    .size(52.dp)
                    .clip(MaterialTheme.shapes.small)
                    .clickable { statusEmojiPicker = !statusEmojiPicker },
                contentAlignment = Alignment.Center,
            ) {
                if (statusEmoji.isBlank()) {
                    Text("😀", fontSize = 24.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(statusEmoji, fontSize = 24.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = statusText,
                onValueChange = { statusText = it.take(64) },
                label = { Text("What's happening?") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }

        if (statusEmojiPicker) {
            EmojiPicker(
                recents = statusRecents,
                onPick = { emoji -> statusEmoji = emoji; statusEmojiPicker = false },
                onClose = { statusEmojiPicker = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    state.setCustomStatus(
                        emoji = statusEmoji.trim().ifEmpty { null },
                        text = statusText.trim().ifEmpty { null },
                    )
                },
                enabled = !state.busy,
            ) { Text("Save") }
            TextButton(onClick = {
                statusEmoji = ""
                statusText = ""
                state.setCustomStatus(emoji = null, text = null)
            }) { Text("Clear") }
        }
    }

    SettingsCard {
        Text("Username", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Your handle is your username plus a number. Several people can be " +
                "“${me.username}” — only one can be “${me.handle}”.\n\n" +
                "Renaming keeps your number when it's free under the new name, so " +
                "${me.username}#${me.discriminator.toString().padStart(4, '0')} → other → " +
                "back gives you the same handle again. If someone claimed it meanwhile, " +
                "you come back with a new number.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it.take(32) },
                label = { Text("Username") },
                supportingText = { Text("2–32 characters: letters, numbers, _ and .") },
                singleLine = true,
                isError = username.isNotBlank() && !USERNAME_RE.matches(username),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = { state.changeUsername(username) },
                enabled = !state.busy &&
                    USERNAME_RE.matches(username) &&
                    username != me.username,
            ) { Text("Change") }
        }
    }

    SettingsCard {
        Text("Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Display name") },
            supportingText = { Text("What people see. Your handle doesn't change.") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pronouns,
            onValueChange = { pronouns = it.take(40) },
            label = { Text("Pronouns") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = bio,
            onValueChange = { bio = it.take(512) },
            label = { Text("About me") },
            supportingText = { Text("${bio.length} / 512") },
            minLines = 3,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = {
                state.saveProfile(
                    displayName.trim().ifEmpty { null },
                    bio.trim(),
                    pronouns.trim(),
                    null,
                )
            },
            enabled = !state.busy,
        ) { Text("Save profile") }
    }
}

/** Same rule the server enforces, so the button disables before the round trip rejects it. */
private val USERNAME_RE = Regex("^[A-Za-z0-9_.]{2,32}$")

/**
 * Your avatar, as [AvatarPicker]'s picture: the photo when there is one, your initial when
 * there isn't.
 *
 * Keyed on [UserDto.avatarKey], never the URL — presigned URLs change on every fetch and would
 * miss the cache on every redraw.
 */
@Composable
internal fun UserAvatar(user: app.singular.client.net.UserDto, label: String, size: Int) =
    Avatar(user, size, label)

// ---------------------------------------------------------------------------
// The remaining sections
// ---------------------------------------------------------------------------

/** Feature 11's other half: undoing a block, which otherwise had nowhere to live. */
@Composable
private fun PrivacySection(state: AppState) {
    val muted = state.channels.filter { state.mutedChannels[it.id] == true }
    val blocked = state.channels
        .flatMap { it.members }
        .filter { it.blockedByViewer && it.id != state.currentUser?.id }
        .distinctBy { it.id }

    SettingsCard {
        Text("Blocked people", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "Their messages collapse behind a notice instead of disappearing, so a " +
                "conversation still reads in order. They aren't told.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (blocked.isEmpty()) {
            Text(
                "You haven't blocked anyone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            blocked.forEach { person ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(person, 32)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(person.label, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            person.handle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { state.unblockUser(person.id) }) { Text("Unblock") }
                }
            }
        }
    }

    // Muting moved here from Notifications. Both cards answer the same question — who and
    // what have I turned down — and splitting them meant checking two screens to find out why
    // something was quiet. Notifications now holds only the settings that apply to the device.
    SettingsCard {
        Text(
            "Muted conversations",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Muted conversations still arrive and still mark the sidebar — they just don't " +
                "raise a notification.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (muted.isEmpty()) {
            Text(
                "Nothing is muted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            muted.forEach { channel ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        channel.title(state.currentUser?.id),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { state.toggleMute(channel.id) }) { Text("Unmute") }
                }
            }
        }
    }
}

@Composable
private fun NotificationsSection(state: AppState) {
    SettingsCard {
        Text("Notifications", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            if (notificationsAvailable) {
                "These apply to this device only. The same account on another machine keeps " +
                    "its own settings, because “be quiet here” rarely means “be quiet everywhere”."
            } else {
                "This device can't show notifications — the system tray is unavailable. " +
                    "Messages still arrive and the sidebar still marks them; nothing pops up."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingToggle(
            title = "Desktop notifications",
            description = "Pop a notification for messages in conversations you don't have open.",
            checked = state.notifyEnabled,
            onCheckedChange = { state.notifyEnabled = it },
            enabled = notificationsAvailable,
        )
        SettingToggle(
            title = "Only when I'm mentioned",
            description = "Stay silent unless a message names you, a role you hold, or @everyone.",
            checked = state.notifyMentionsOnly,
            onCheckedChange = { state.notifyMentionsOnly = it },
            // Pointless while notifications are off entirely, so it greys out rather than
            // sitting there implying it still does something.
            enabled = notificationsAvailable && state.notifyEnabled,
        )
        SettingToggle(
            title = "Show message text",
            description = "Off shows only “New message” — useful when you share your screen.",
            checked = state.notifyPreviews,
            onCheckedChange = { state.notifyPreviews = it },
            enabled = notificationsAvailable && state.notifyEnabled,
        )

        HorizontalDivider()
        Text(
            "Muting a single conversation is on that conversation, from the bell in its " +
                "header. Muted ones are listed under Privacy.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutSection() {
    SettingsCard {
        Text("Singular", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            "A Kotlin Multiplatform client drawn with Compose — no webview, no bundled " +
                "browser. Every library is vendored offline.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        Text("Keyboard shortcuts", style = MaterialTheme.typography.labelLarge)
        // The same list F1 shows, from the same source — so it cannot drift.
        Shortcuts.entries.forEach { entry ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Text(
                    entry.keys,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.width(170.dp),
                )
                Text(
                    entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
