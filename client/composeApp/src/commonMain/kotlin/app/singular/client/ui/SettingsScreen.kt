package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.singular.client.AppState

/**
 * Settings.
 *
 * Chat layout lives here rather than in the conversation header, where it started. It's a
 * preference you set once and forget, not an action you take on a particular chat â€” putting it
 * beside Mute and Block implied it was per-conversation, which it never was.
 */
@Composable
fun SettingsScreen(state: AppState, onClose: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose) { Text("Done") }
        }

        AppearanceSection(state)
        ProfileSection(state)

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ---------------------------------------------------------------------------
// Appearance â€” layout + theme (feature 16)
// ---------------------------------------------------------------------------

@Composable
private fun AppearanceSection(state: AppState) {
    Card(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Dark theme", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (state.themeDark == null) "Following your system setting"
                        else "Set manually",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = state.themeDark ?: true,
                    onCheckedChange = { state.setThemeDark(it) },
                )
            }
        }
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
private fun ProfileSection(state: AppState) {
    val me = state.currentUser ?: return
    var displayName by remember(me.id) { mutableStateOf(me.displayName ?: me.username) }
    var bio by remember(me.id) { mutableStateOf(me.bio ?: "") }
    var pronouns by remember(me.id) { mutableStateOf(me.pronouns ?: "") }

    Card(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Profile", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(me.id, displayName.ifBlank { me.username }, 56)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        displayName.ifBlank { me.username },
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // The handle is fixed and shown for reference â€” it's how people add you,
                    // and the number is reallocated if you ever rename.
                    Text(
                        me.handle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

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
}
