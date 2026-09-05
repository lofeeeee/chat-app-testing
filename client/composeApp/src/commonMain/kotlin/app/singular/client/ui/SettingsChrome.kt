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
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
            .width(240.dp)
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
    Card(Modifier.fillMaxWidth().widthIn(max = 720.dp)) {
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
 * A picture that is also the control that changes it.
 *
 * A camera badge over the corner, because a bare circle gives no hint it is clickable — and a
 * picture that silently ignores a click is the single most common thing people report as broken
 * in a settings screen. Your avatar and a server's icon are the same widget at different sizes,
 * which is why they are one composable with a [size] rather than two near-identical ones.
 */
@Composable
fun AvatarPicker(
    size: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    picture: @Composable () -> Unit,
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        picture()

        // Always on top of whichever picture is showing: the affordance has to be visible when
        // there *is* a picture, which is exactly when you might want to replace it. The badge
        // scales with the circle so an 84dp avatar and a 64dp icon get proportionate affordances
        // rather than one badge size that dwarfs the smaller of the two.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(size * BADGE_RATIO)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PhotoCamera,
                contentDescription = "Change picture",
                modifier = Modifier.size(size * BADGE_RATIO * 0.58f),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/** Camera badge diameter as a fraction of the circle's, measured off the original 84dp picker. */
private val BADGE_RATIO = 26f / 84f
