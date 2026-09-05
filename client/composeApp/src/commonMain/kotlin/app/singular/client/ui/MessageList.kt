package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.singular.client.net.MessageDto
import app.singular.client.net.ReactionDto
import app.singular.client.net.UserDto

/** Which of the two chat presentations to draw. */
enum class ChatLayout { BUBBLES, COMPACT }

/**
 * One message plus everything the renderer needs to know about its neighbours.
 *
 * Grouping is computed once here rather than inside the row, because a row can't see the
 * message above it without the caller passing it in anyway — and doing it per row would mean
 * re-deriving the same booleans on every recomposition of every row.
 */
data class RenderedMessage(
    val message: MessageDto,
    val mine: Boolean,
    /** First message of a run by this author — the one that carries the name and avatar. */
    val startsGroup: Boolean,
    /** Last of the run — the one that carries the timestamp in bubble layout. */
    val endsGroup: Boolean,
)

/**
 * Splits a flat list into author runs.
 *
 * A run breaks on a different author or a gap longer than [GROUP_WINDOW_MINUTES]. The time
 * break matters: without it, two messages from the same person five hours apart render as one
 * continuous block, which reads as though they were said together.
 */
fun groupMessages(messages: List<MessageDto>, selfId: String?): List<RenderedMessage> =
    messages.mapIndexed { index, message ->
        val prev = messages.getOrNull(index - 1)
        val next = messages.getOrNull(index + 1)

        RenderedMessage(
            message = message,
            mine = message.author.id == selfId,
            startsGroup = prev == null ||
                prev.author.id != message.author.id ||
                minutesBetween(prev.createdAt, message.createdAt) > GROUP_WINDOW_MINUTES,
            endsGroup = next == null ||
                next.author.id != message.author.id ||
                minutesBetween(message.createdAt, next.createdAt) > GROUP_WINDOW_MINUTES,
        )
    }

private const val GROUP_WINDOW_MINUTES = 5

// ---------------------------------------------------------------------------
// Rich message text: emoji font + mention highlighting
// ---------------------------------------------------------------------------

/**
 * The mention/entity wire format, mirrored from the server's MentionParser.
 *
 * Kept as one pattern so a message renders in a single pass — three separate scans would
 * highlight a `<@1>` inside a `<#123>` channel ref as a broken user mention.
 */
private val MENTION_PATTERN = Regex("""<@&?(\d{1,20})>|<#(\d{1,20})>""")

/**
 * Renders message content as an AnnotatedString:
 *
 * - `<@id>` / `<@&roleId>` / `<#channelId>` spans become `@Name` / `@RoleName` / `#channel`,
 *   tinted like a link with a soft background. Substitution happens at display time on
 *   purpose — the same reasoning as the server's mention table: a user who renames re-renders
 *   everywhere they were ever mentioned, rather than freezing their old name into history.
 * - emoji runs render through the bundled Noto face, via explicit spans (Compose has no
 *   automatic custom-font fallback).
 */
@Composable
fun messageAnnotated(
    content: String,
    color: androidx.compose.ui.graphics.Color,
    resolver: MentionResolver,
): androidx.compose.ui.text.AnnotatedString {
    val emojiFont = emojiFontFamily()
    return remember(content, resolver.revision, color) {
        buildAnnotatedString {
            var cursor = 0

            // Emoji-run-aware append: walk the runs in order, plain text between them goes
            // out unstyled, emoji runs get the bundled font as an explicit span (Compose has
            // no automatic custom-font fallback).
            fun appendWithEmoji(text: String) {
                var at = 0
                for (range in emojiRunRanges(text)) {
                    if (range.first > at) append(text.substring(at, range.first))
                    val start = length
                    append(text.substring(range.first, range.last + 1))
                    addStyle(SpanStyle(fontFamily = emojiFont), start, length)
                    at = range.last + 1
                    if (at >= text.length) return
                }
                if (at < text.length) append(text.substring(at))
            }

            for (match in MENTION_PATTERN.findAll(content)) {
                if (match.range.first > cursor) appendWithEmoji(content.substring(cursor, match.range.first))

                val display = resolver.displayFor(match.value)
                val start = length
                append(display)
                // A mention can carry an emoji in no name we recognise, but the tint is what
                // matters; names are plain text.
                addStyle(
                    SpanStyle(
                        fontWeight = FontWeight.SemiBold,
                        background = color.copy(alpha = 0.12f),
                    ),
                    start,
                    length,
                )
                cursor = match.range.last + 1
            }
            if (cursor < content.length) appendWithEmoji(content.substring(cursor))
        }
    }
}

/**
 * Resolves a wire mention (`<@id>`, `<@&id>`, `<#id>`) to display text.
 *
 * [revision] changes whenever the underlying user/role/channel data does, so the annotated
 * string's remember key stays honest.
 */
class MentionResolver(
    private val usersById: Map<String, UserDto>,
    private val rolesById: Map<String, String>,
    private val channelsById: Map<String, String>,
    private val selfId: String?,
) {
    val revision: Int = usersById.hashCode() * 31 + rolesById.hashCode()

    fun displayFor(wire: String): String = when {
        wire.startsWith("<@&") -> "@" + (rolesById[wire.removePrefix("<@&").removeSuffix(">")] ?: "role")
        wire.startsWith("<#") -> "#" + (channelsById[wire.removePrefix("<#").removeSuffix(">")] ?: "channel")
        wire.startsWith("<@") -> {
            val id = wire.removePrefix("<@").removeSuffix(">")
            val user = usersById[id]
            when {
                user != null -> "@" + user.label
                id == selfId -> "@you"
                else -> "@user"
            }
        }
        else -> wire
    }

    /** Whether any user mention in this content points at me — drives the "you were tagged" tint. */
    fun mentionsMe(content: String?): Boolean {
        if (content == null) return false
        return MENTION_PATTERN.findAll(content).any { m ->
            val id = m.groupValues[1].ifEmpty { m.groupValues[2] }
            val isUser = m.value.startsWith("<@") && !m.value.startsWith("<@&")
            isUser && id == selfId
        } || content.contains("@everyone") || content.contains("@here")
    }
}

/**
 * Message body: attachments, rich text (mentions + emoji font), reactions.
 */
@Composable
private fun MessageBody(
    message: MessageDto,
    foreground: androidx.compose.ui.graphics.Color,
    resolver: MentionResolver,
    onReact: (emoji: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AttachmentBlock(message.attachments, message.location, foreground)

        if (!message.content.isNullOrBlank()) {
            Text(
                messageAnnotated(message.content, foreground, resolver),
                style = MaterialTheme.typography.bodyLarge,
                color = foreground,
            )
        }

        if (message.reactions.isNotEmpty()) {
            ReactionChips(message.reactions, onReact)
        }
    }
}

/**
 * The reaction chips under a message: one pill per emoji, count inside, highlighted when
 * the viewer reacted. Tapping toggles your own reaction.
 */
@Composable
fun ReactionChips(
    reactions: List<app.singular.client.net.ReactionDto>,
    onReact: (String) -> Unit,
) {
    val emojiFont = emojiFontFamily()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        reactions.forEach { reaction ->
            val mine = reaction.me
            val shape = RoundedCornerShape(999.dp)
            Surface(
                onClick = { onReact(reaction.emoji) },
                shape = shape,
                color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant,
                contentColor = if (mine) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                border = if (mine) androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                ) else null,
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(reaction.emoji, fontSize = 14.sp, fontFamily = emojiFont)
                    Text(
                        reaction.count.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mine) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Minutes between two ISO-8601 instants.
 *
 * Parsed by hand rather than with kotlinx-datetime: this is the only date arithmetic in the
 * client, and it isn't worth a dependency. Anything unparseable returns a large number, which
 * breaks the group — the safe direction, since a wrongly split run is merely ugly while a
 * wrongly merged one misattributes who said what and when.
 */
private fun minutesBetween(a: String, b: String): Long {
    val ma = epochMinutes(a) ?: return Long.MAX_VALUE
    val mb = epochMinutes(b) ?: return Long.MAX_VALUE
    return (mb - ma)
}

private fun epochMinutes(iso: String): Long? {
    // "2026-09-02T21:40:12.123Z"
    val date = iso.substringBefore('T')
    val time = iso.substringAfter('T', "")
    val (y, mo, d) = date.split('-').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 3 }
        ?: return null
    val hh = time.take(2).toIntOrNull() ?: return null
    val mm = time.drop(3).take(2).toIntOrNull() ?: return null

    // Days since an arbitrary epoch. Exact calendar correctness isn't needed — only the
    // difference between two nearby instants is ever used.
    val days = y.toLong() * 365 + y / 4 - y / 100 + y / 400 + MONTH_DAYS[mo - 1] + d
    return days * 24 * 60 + hh * 60 + mm
}

private val MONTH_DAYS = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)

/** "21:40" out of an ISO instant. */
fun shortTime(iso: String): String {
    val time = iso.substringAfter('T', "")
    return if (time.length >= 5) time.take(5) else ""
}

@Composable
fun MessageList(
    messages: List<MessageDto>,
    selfId: String?,
    layout: ChatLayout,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    resolver: MentionResolver = remember(selfId) { MentionResolver(emptyMap(), emptyMap(), emptyMap(), selfId) },
    onReact: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    onMessageLongPress: (MessageDto) -> Unit = {},
) {
    val rendered = remember(messages.toList(), selfId) { groupMessages(messages, selfId) }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(rendered, key = { it.message.id }) { row ->
            // A little air between runs, none within one. This spacing is what actually makes
            // grouping read as grouping.
            if (row.startsGroup) Spacer(Modifier.size(10.dp))

            when (layout) {
                ChatLayout.BUBBLES -> BubbleRow(row, resolver, onReact, onMessageLongPress)
                ChatLayout.COMPACT -> CompactRow(row, resolver, onReact, onMessageLongPress)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Layout 1 — bubbles, WhatsApp-style
// ---------------------------------------------------------------------------

@Composable
private fun BubbleRow(
    row: RenderedMessage,
    resolver: MentionResolver,
    onReact: (String, String) -> Unit,
    onMessageLongPress: (MessageDto) -> Unit,
) {
    val message = row.message

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (row.mine) Arrangement.End else Arrangement.Start,
    ) {
        // Avatar only on the last message of an incoming run, so it sits beside the tail of the
        // bubble stack the way a speaker's portrait does. A blank keeps the column aligned.
        if (!row.mine) {
            if (row.endsGroup) {
                Avatar(message.author.id, message.author.label, size = 28)
            } else {
                Spacer(Modifier.size(28.dp))
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(
            horizontalAlignment = if (row.mine) Alignment.End else Alignment.Start,
            modifier = Modifier.widthIn(max = 520.dp),
        ) {
            // Display name only — never the handle. Handles are for finding people, not for
            // reading a conversation; nobody wants "Orbit#2989" above every line.
            if (row.startsGroup && !row.mine) {
                Text(
                    message.author.label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    // accentSoft: a name is text, and needs the accent variant that is legible
                    // as text on the canvas. `primary` is a fill colour doing a text job.
                    color = LocalSingularColors.current.accentSoft,
                    modifier = Modifier.padding(start = 12.dp, bottom = 3.dp),
                )
            }

            Bubble(row, resolver, onReact, onMessageLongPress)
        }

        if (row.mine) Spacer(Modifier.width(8.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(
    row: RenderedMessage,
    resolver: MentionResolver,
    onReact: (String, String) -> Unit,
    onMessageLongPress: (MessageDto) -> Unit,
) {
    val message = row.message
    var revealed by remember(message.id) { mutableStateOf(false) }

    // Square off the corner facing the previous bubble in the run, so a stack reads as one
    // block instead of a column of identical lozenges. Values match SingularShapes.large /
    // extraSmall — referenced directly rather than via the shapes object because this composable
    // needs per-corner control, which the Shapes slots can't express.
    val r = 18.dp
    val tight = 6.dp
    val shape = if (row.mine) {
        RoundedCornerShape(r, if (row.startsGroup) r else tight, if (row.endsGroup) r else tight, r)
    } else {
        RoundedCornerShape(if (row.startsGroup) r else tight, r, r, if (row.endsGroup) r else tight)
    }

    val background =
        if (row.mine) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.surfaceVariant
    val foreground =
        if (row.mine) MaterialTheme.colorScheme.onPrimary
        else MaterialTheme.colorScheme.onSurfaceVariant

    // A message that mentions me gets an accent bar — WhatsApp's "you were tagged" affordance.
    val mentioned = resolver.mentionsMe(message.content)

    Box(
        Modifier
            .clip(shape)
            .background(background)
            .combinedClickable(onClick = {}, onLongClick = { onMessageLongPress(message) })
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        if (mentioned) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(3.dp, 20.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp))
            )
        }
        if (message.authorBlocked && !revealed) {
            TextButton(onClick = { revealed = true }) { Text("Blocked message — show") }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MessageBody(message, foreground, resolver) { emoji ->
                    onReact(message.id, emoji)
                }

                // Time inside the bubble, trailing the text. An attachment-only message still
                // needs the timestamp, so the row renders even with no body.
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        shortTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = foreground.copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Layout 2 — compact, Discord-style
// ---------------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CompactRow(
    row: RenderedMessage,
    resolver: MentionResolver,
    onReact: (String, String) -> Unit,
    onMessageLongPress: (MessageDto) -> Unit,
) {
    val message = row.message
    var revealed by remember(message.id) { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.Top) {
        // Avatar on the first of a run only. Continuation lines get a blank gutter of the same
        // width, which is what keeps the text edges aligned down the whole column.
        if (row.startsGroup) {
            Avatar(message.author.id, message.author.label, size = 36)
        } else {
            Spacer(Modifier.size(36.dp))
        }
        Spacer(Modifier.width(12.dp))

        Column(
            Modifier
                .weight(1f)
                .combinedClickable(onClick = {}, onLongClick = { onMessageLongPress(message) })
        ) {
            if (row.startsGroup) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        message.author.label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        // Your own name in the accent colour, like Discord's "you" highlight.
                        // accentSoft rather than primary: primary is tuned to be a fill behind
                        // onAccent, and as text on a dark canvas it can be too dim to read.
                        color = if (row.mine) LocalSingularColors.current.accentSoft
                                else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        shortTime(message.createdAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (message.authorBlocked && !revealed) {
                TextButton(onClick = { revealed = true }) { Text("Blocked message — show") }
            } else {
                MessageBody(message, MaterialTheme.colorScheme.onSurface, resolver) { emoji ->
                    onReact(message.id, emoji)
                }
            }
        }
    }
}

@Composable
internal fun Avatar(seed: String, label: String, size: Int) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(avatarColor(seed)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.take(1).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = onOf(avatarColor(seed)),
        )
    }
}
