package app.singular.client.ui

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import app.singular.client.platform.PickedFile
import androidx.compose.ui.unit.dp

/**
 * The chrome shared by the settings screens.
 *
 * App settings and server settings are the same idea at different scopes — a narrow nav of
 * grouped sections beside a scrolling column of cards — and the two were first built as one
 * screen and one dialog. Extracted here so the nav, the card, and the picker-with-camera-badge
 * exist once: a layout that drifts between two copies of itself is a bug you find by comparing
 * screenshots, which is the slowest possible way.
 */

/**
 * One row of a [SettingsNav]: where it goes, and the words beside it.
 *
 * The blurb is half the reason the nav works — "Appearance" alone could be themes, fonts or
 * language, and "Theme and chat layout" is the answer you'd otherwise have to click for.
 */
class SettingsNavItem<T>(
    val value: T,
    val title: String,
    val blurb: String,
)

/**
 * The settings nav column: a back arrow and title, the section list, then [footer] pinned to
 * the bottom.
 *
 * Generic over the section type so the app and server screens pass their own enums rather than
 * both going through a String key that cannot be exhausted in a `when`.
 */
@Composable
fun <T> SettingsNav(
    title: String,
    items: List<SettingsNavItem<T>>,
    selected: T,
    onPick: (T) -> Unit,
    onClose: () -> Unit,
    footer: @Composable () -> Unit = {},
) {
    Column(
        Modifier
            .width(panelWidth(expanded = 240.dp, medium = 208.dp, compact = 172.dp))
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surface)
            .padding(vertical = 10.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A back arrow, matching Stories. "Done" implied there was something to submit;
            // settings screens save as you go or stage their own saves.
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider()
        Spacer(Modifier.height(6.dp))

        items.forEach { entry ->
            val active = entry.value == selected
            Column(
                // Two paddings, on purpose: the outer one keeps the highlight off the nav's
                // edge, the inner one puts air between the highlight and the words.
                Modifier
                    .padding(horizontal = 8.dp, vertical = 1.dp)
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(
                        if (active) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent
                    )
                    .clickable { onPick(entry.value) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                )
                Text(
                    entry.blurb,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.weight(1f))
        HorizontalDivider()
        footer()
    }
}

/**
 * The scrolling half of a settings screen: its section's title at the top, then [content].
 *
 * Cards inside are capped at 720dp by [SettingsCard], so this column can fill any window width
 * while its contents stay a readable measure.
 */
@Composable
fun SettingsPane(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        content()
    }
}

/** One grouped card of settings. The only container a settings section needs. */
@Composable
fun SettingsCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {   // capped by the pane it sits in
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            content()
        }
    }
}

/** The title of a [SettingsCard]: the card's name, in the one style every card uses. */
@Composable
fun SettingsCardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

/**
 * A picture that opens a dialog for viewing and replacing it.
 *
 * The camera badge is gone. It was there to prove the circle was clickable, but a badge that
 * overlaps the very picture it describes is a poor trade — it clips the face at small sizes and
 * it still only ever led to a file dialog, so there was no way to *look* at the picture you
 * already had. Clicking now opens something that does both jobs, which is worth the extra step.
 *
 * Your avatar and a server's icon are the same widget at different sizes, which is why this is
 * one composable with a [size] rather than two near-identical ones — and why fixing this fixed
 * both at once.
 */
@Composable
fun AvatarPicker(
    size: Dp,
    enabled: Boolean,
    /** Opens the file picker and hands back what was chosen, uploading nothing. */
    onPick: ((PickedFile) -> Unit) -> Unit,
    /** Uploads the final, cropped bytes. */
    onUpload: (PickedFile) -> Unit,
    title: String = "Profile picture",
    uploadLabel: String = "Upload profile picture",
    /** Draws the picture at the size it is given — the circle and the dialog differ. */
    picture: @Composable (Dp) -> Unit,
) {
    var open by remember { mutableStateOf(false) }

    // Set once a file has been chosen and until the crop is confirmed or cancelled. Holding it
    // here rather than in AppState keeps a half-finished crop out of application state: back
    // out and nothing anywhere has changed.
    var cropping by remember { mutableStateOf<PickedFile?>(null) }

    cropping?.let { file ->
        ImageCropperDialog(
            file = file,
            title = "Crop $title".lowercase().replaceFirstChar { it.uppercase() },
            onCancel = { cropping = null },
            onConfirm = { cropped -> cropping = null; onUpload(cropped) },
        )
    }

    if (open) {
        AvatarDialog(
            title = title,
            uploadLabel = uploadLabel,
            enabled = enabled,
            // The view dialog closes as the picker opens: two modal windows stacked, one of
            // them the OS file chooser, is a fight over the foreground nobody wins.
            onUpload = { open = false; onPick { picked -> cropping = picked } },
            onClose = { open = false },
            picture = picture,
        )
    }

    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled) { open = true },
        contentAlignment = Alignment.Center,
    ) {
        picture(size)
    }
}

/**
 * The picture, big, with the one action that applies to it.
 *
 * Deliberately large — 220dp of picture. The whole reason to open a dialog rather than jump
 * straight to the file chooser is to *see* what you currently have, and a thumbnail in a
 * dialog would be the same thumbnail you just clicked.
 *
 * The same [picture] composable is reused rather than re-fetched, so whatever the caller draws
 * in the circle — a remote image, initials, a server's monogram — appears here too and cannot
 * disagree with it.
 */
@Composable
private fun AvatarDialog(
    title: String,
    uploadLabel: String,
    enabled: Boolean,
    onUpload: () -> Unit,
    onClose: () -> Unit,
    /** Draws the picture at the size it is given — the circle and the dialog differ. */
    picture: @Composable (Dp) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(title) },
        text = {
            DialogKeys(onDismiss = onClose) {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Box(
                        Modifier
                            .size(220.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        // The caller draws at whatever size it is handed, so the dialog gets
                        // a genuinely large rendering rather than an 84dp thumbnail scaled up
                        // into a blur.
                        picture(220.dp)
                    }

                    Button(
                        onClick = onUpload,
                        enabled = enabled,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(uploadLabel) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}
