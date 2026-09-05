package app.singular.client.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The two unread affordances, defined once so the rail, the channel list and a collapsed
 * category cannot drift apart.
 *
 * They deliberately say different things:
 *
 *  * **Unread** — something was said here. A weight and colour change, or a small bar on the
 *    rail. Ambient; you can scroll past it.
 *  * **Mentions** — you were addressed. A red badge with a count. Not ambient, and never
 *    merged into the unread state, because "the channel is busy" and "someone asked you a
 *    question" are not the same news.
 */

/**
 * The red mention badge.
 *
 * Shows the number up to nine, and past that just the dot. A two-digit count in a 16dp circle
 * is unreadable at a glance, and the difference between 12 and 30 changes nothing about what
 * you do next — you open it either way. So the badge stops counting and simply insists.
 */
@Composable
fun MentionBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return

    val red = MaterialTheme.colorScheme.error
    val on = MaterialTheme.colorScheme.onError

    if (count > 9) {
        Box(modifier.size(9.dp).clip(CircleShape).background(red))
        return
    }

    Box(
        modifier
            .defaultMinSize(minWidth = 16.dp, minHeight = 16.dp)
            .clip(CircleShape)
            .background(red)
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            count.toString(),
            color = on,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            // The badge is small enough that the default line height pushes the glyph off
            // centre; matching it to the font size puts the digit back in the middle.
            style = MaterialTheme.typography.labelSmall.copy(lineHeight = 10.sp),
        )
    }
}

/**
 * The white-on-dark / black-on-light bar at the left edge of a server tile.
 *
 * `onSurface` rather than a literal white: it is near-white on every dark preset and near-black
 * on every light one, which is exactly the "opposite of the background" this needs to be, and
 * it stays correct when someone switches theme.
 *
 * Three sizes, and the height carries the meaning — selected is a tall bar, unread is a stub,
 * read is nothing. Animated so switching servers reads as one bar moving rather than two
 * unrelated redraws.
 */
@Composable
fun RailIndicator(selected: Boolean, unread: Boolean, modifier: Modifier = Modifier) {
    val target = when {
        selected -> 28.dp
        unread -> 9.dp
        else -> 0.dp
    }
    val height by animateDpAsState(targetValue = target, label = "rail-indicator")

    Box(
        modifier
            .padding(start = 2.dp)
            .width(4.dp)
            .height(height)
            // Rounded on the outer edge only, so it reads as attached to the rail rather than
            // floating beside it.
            .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
            .background(MaterialTheme.colorScheme.onSurface)
    )
}

// ---------------------------------------------------------------------------
// Form controls
// ---------------------------------------------------------------------------

/**
 * A labelled switch where the **whole row** is the target, not just the switch.
 *
 * Hitting a 52×32 control to change a setting whose label is right there is needless
 * precision, and it is the convention everywhere else — a `<label>` wrapping its input on the
 * web, a settings row on both mobile platforms. `toggleable` on the row rather than a
 * `clickable` plus an `onCheckedChange` is what makes that one control to a screen reader
 * instead of two that happen to sit together; the switch itself takes `null` for exactly that
 * reason.
 */
@Composable
fun SettingToggle(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // null, not a second handler: the row above owns the gesture, and giving the switch
        // its own would register two overlapping targets and announce the setting twice.
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** The same idea for a radio choice: the label picks it, not just the dot. */
@Composable
fun SettingRadio(
    title: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onSelect,
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
