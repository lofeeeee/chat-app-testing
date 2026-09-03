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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.singular.client.AppState

/**
 * Settings.
 *
 * Chat layout lives here rather than in the conversation header, where it started. It's a
 * preference you set once and forget, not an action you take on a particular chat — putting it
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
// Appearance — layout + theme (feature 16)
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

            Text("Accent colours", style = MaterialTheme.typography.labelLarge)
            Text(
                "Everything else is derived from these. Colours too close to the background " +
                    "are nudged until they stay readable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("Primary", style = MaterialTheme.typography.labelMedium)
            SwatchRow(state.themePrimary) { state.setTheme(it, null, null) }
            Text("Secondary", style = MaterialTheme.typography.labelMedium)
            SwatchRow(state.themeSecondary) { state.setTheme(null, it, null) }

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
                    onCheckedChange = { state.setTheme(null, null, it) },
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
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(10.dp),
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
                    if (mine) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surface
                )
        )
    }
}

@Composable
private fun CompactPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Only the first row of a run carries an avatar — the same rule the real
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
 * A row of preset colours plus the current one.
 *
 * Presets rather than a free colour picker on purpose: the theme derives a whole palette from
 * these, and a hand-picked near-black primary produces a technically valid but unusable app.
 * [ensureContrast] catches the worst of it, but not offering the trap is better than fixing it.
 */
@Composable
private fun SwatchRow(current: Int?, onPick: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SWATCHES.forEach { rgb ->
            val selected = current == rgb
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF000000L.toInt() or rgb))
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.outline,
                        shape = CircleShape,
                    )
                    .clickable { onPick(rgb) }
            )
        }
    }
}

private val SWATCHES = listOf(
    0x3D5AFE, // indigo
    0x00BFA5, // teal
    0x23A55A, // green
    0xF0B232, // amber
    0xF23F43, // red
    0xB57EDC, // lilac
    0xEC4899, // pink
)

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
                    // The handle is fixed and shown for reference — it's how people add you,
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
