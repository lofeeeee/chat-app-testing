package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.singular.client.net.ChannelDto
import app.singular.client.net.UserDto

import androidx.compose.material3.Typography

// ---------------------------------------------------------------------------
// S1  Font standardisation
// ---------------------------------------------------------------------------

/**
 * Typography shortcuts used across call sites.
 *
 * Material's `Typography` object doesn't carry semi-bold variants, and
 * `.copy(fontWeight = FontWeight.SemiBold)` 20 times is 20 opportunities for
 * the copies to drift. Extension properties keep them standard.
 */
val Typography.bodyMediumSemiBold: TextStyle
    get() = bodyMedium.copy(fontWeight = FontWeight.SemiBold)

val Typography.titleSmallSemiBold: TextStyle
    get() = titleSmall.copy(fontWeight = FontWeight.SemiBold)

val Typography.titleMediumSemiBold: TextStyle
    get() = titleMedium.copy(fontWeight = FontWeight.SemiBold)

val Typography.labelLargeSemiBold: TextStyle
    get() = labelLarge.copy(fontWeight = FontWeight.SemiBold)

val Typography.headlineSmallBold: TextStyle
    get() = headlineSmall.copy(fontWeight = FontWeight.Bold)

/** Emoji sizes, in one place so the picker, status editor and story editor agree. */
object EmojiSize {
    val picker = 22.sp
    val status = 24.sp
    val editor = 26.sp
    val reaction = 14.sp
    val messageInline = 16.sp
}

/** Brand letterSpacing used in the login wordmark. */
val BrandLetterSpacing = (-0.5).sp

/** Keyboard-hint letterSpacing (the "ESC" pill). */
val KbdLetterSpacing = 0.5.sp

// ---------------------------------------------------------------------------
// S2  Sizing standardisation
// ---------------------------------------------------------------------------

/** Avatar sizes.  Every avatar in the app should pick from this set. */
object AvatarSize {
    /** Sidebar rows, friends list, profile bar. */
    val sm = 32.dp
    /** DM row avatar. */
    val row = 34.dp
    /** Medium — conversation header, member list (future). */
    val md = 40.dp
    /** Settings profile card. */
    val lg = 84.dp
    /** Full-size preview in avatar dialog. */
    val preview = 220.dp
}

/** Icon sizes.  Named by relative weight, not by where they're used. */
object IconSize {
    val xs = 14.dp
    val sm = 16.dp
    val md = 18.dp
    val lg = 20.dp
    val xl = 24.dp
}

// ---------------------------------------------------------------------------
// S3  Code compaction — shared composables + extension helpers
// ---------------------------------------------------------------------------

/** Selected-row background: `surfaceVariant` when active, `surface` when not. */
@Composable
fun selectedBackground(selected: Boolean): Color =
    if (selected) MaterialTheme.colorScheme.surfaceVariant
    else MaterialTheme.colorScheme.surface

/** Emphasis text weight for selected/unread items. */
fun emphasisWeight(on: Boolean): FontWeight =
    if (on) FontWeight.SemiBold else FontWeight.Normal

/** Emphasis ink: brighter when selected/unread, muted otherwise. */
@Composable
fun emphasisColor(on: Boolean): Color =
    if (on) MaterialTheme.colorScheme.onSurface
    else MaterialTheme.colorScheme.onSurfaceVariant

/** The other member of a DM channel. */
fun ChannelDto.otherMember(selfId: String?): UserDto? =
    members.firstOrNull { it.id != selfId }

/**
 * The unread indicator shared by DM rows and guild channel rows.
 *
 * Shows a red mention badge when mentions > 0, an unread dot otherwise, nothing when read.
 * Extracts the identical 10-line block that existed in two places.
 */
@Composable
fun UnreadIndicator(unread: Boolean, mentions: Int) {
    if (mentions > 0) {
        MentionBadge(mentions)
        Spacer(Modifier.width(Spacing.xs))
    } else if (unread) {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface)
        )
        Spacer(Modifier.width(Spacing.xs))
    }
}
