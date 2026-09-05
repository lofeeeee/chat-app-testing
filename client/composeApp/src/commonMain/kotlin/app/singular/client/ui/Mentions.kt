package app.singular.client.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import app.singular.client.net.GuildMemberDto
import app.singular.client.net.UserDto

/**
 * Mention machinery for the composer and the renderer.
 *
 * The wire format is the server's — `<@123>` user, `<@&456>` role, `<#789>` channel, plus the
 * literal `@everyone` / `@here` — and this file is the one place that knows how to turn it
 * both ways: typed text → wire (the composer), wire → display (the renderer).
 */

/** What the composer offers in its autocomplete list. */
sealed interface MentionCandidate {
    val label: String
    val detail: String?

    data class User(val user: UserDto, val guildName: String? = null) : MentionCandidate {
        override val label: String get() = guildName ?: user.label
        override val detail: String get() = user.handle
    }

    data class Role(val id: String, val name: String) : MentionCandidate {
        override val label: String get() = name
        override val detail: String = ""
    }

    data class Special(val keyword: String, val description: String) : MentionCandidate {
        override val label: String get() = "@$keyword"
        override val detail: String get() = description
    }
}

/**
 * The active `@token` in a draft, if any.
 *
 * Returns the token's start index and the text after the `@`, or null when the caret is not
 * inside a mention token. A token starts at an `@` that is itself at the start of the text or
 * preceded by whitespace — so an email address mid-word never triggers the popup.
 */
data class MentionToken(val start: Int, val query: String)

fun activeMentionToken(draft: String, caret: Int): MentionToken? {
    if (caret > draft.length) return null
    val uptoCaret = draft.substring(0, caret)
    val at = uptoCaret.lastIndexOf('@')
    if (at == -1) return null
    val afterAt = uptoCaret.substring(at + 1)
    // Still typing the same token: no whitespace or another @ since the trigger character.
    if (afterAt.contains(' ') || afterAt.contains('@') || afterAt.contains('\n')) return null
    // The @ itself must start a word (or the draft).
    if (at > 0) {
        val prev = draft[at - 1]
        if (!prev.isWhitespace()) return null
    }
    return MentionToken(start = at, query = afterAt)
}

/**
 * Inserts a picked candidate into the draft, replacing the active `@token` with the wire
 * representation, and returns the new draft plus where the caret should land (just past the
 * inserted text, so a space is one keystroke away).
 */
fun applyMention(draft: String, token: MentionToken, candidate: MentionCandidate): Pair<String, Int> {
    val wire = when (candidate) {
        is MentionCandidate.User -> "<@${candidate.user.id}>"
        is MentionCandidate.Role -> "<@&${candidate.id}>"
        is MentionCandidate.Special -> "@${candidate.keyword}"
    }
    val before = draft.substring(0, token.start)
    val after = draft.substring(token.start + 1 + token.query.length)
    val inserted = "$wire "
    return (before + inserted + after) to (before.length + inserted.length)
}

/**
 * Candidates matching a query, in the order the popup shows them.
 *
 * Specials (@everyone/@here) first, then members filtered on display name *and* handle —
 * people are known by either — then mentionable roles. Only members of the current server
 * are offered: someone outside it can't be mentioned into a channel they can't see.
 */
fun mentionCandidates(
    query: String,
    members: List<GuildMemberDto>,
    dmMembers: List<UserDto>,
    roles: List<app.singular.client.net.RoleDto>,
    isGuildChannel: Boolean,
    limit: Int = 8,
): List<MentionCandidate> {
    val q = query.trim().lowercase()
    val out = mutableListOf<MentionCandidate>()

    if (isGuildChannel) {
        if ("everyone".startsWith(q)) out += MentionCandidate.Special("everyone", "Notify everyone in this server")
        if ("here".startsWith(q)) out += MentionCandidate.Special("here", "Notify only those online")
    }

    val memberSource: List<Pair<String, UserDto>> =
        if (isGuildChannel) members.map { it.displayName to it.user }
        else dmMembers.map { it.label to it }

    memberSource
        .filter { (label, user) ->
            q.isEmpty() || label.lowercase().contains(q) || user.handle.lowercase().contains(q)
        }
        .sortedBy { (label, _) -> label.lowercase() }
        .take(limit)
        .forEach { (label, user) ->
            // In a guild, prefer the server-specific display name; fall back elsewhere.
            out += if (isGuildChannel) MentionCandidate.User(user, label) else MentionCandidate.User(user)
        }

    // Roles are offered for an empty query too. Requiring at least one character meant a bare
    // `@` listed people only, so unless you already knew a role's name you'd never discover
    // you could mention one.
    if (isGuildChannel) {
        roles.filter { it.mentionable && !it.isDefault && (q.isEmpty() || it.name.lowercase().contains(q)) }
            .take(4)
            .forEach { out += MentionCandidate.Role(it.id, it.name) }
    }

    return out.take(limit + 2)
}

// ---------------------------------------------------------------------------
// Composer display
// ---------------------------------------------------------------------------

/**
 * Shows `@Orbit` in the composer while the draft still holds `<@221239600520105984>`.
 *
 * The draft has to stay in the wire format: it is what gets sent, and it is the only form that
 * survives a rename — the server stores the id and every client re-resolves the name at
 * display time. But showing that raw token to the person typing is indefensible; they picked a
 * human out of a list and got a wall of digits back.
 *
 * A [VisualTransformation] is exactly the right tool: it changes what is drawn without
 * touching the value. The alternative — keeping `@Orbit` in the field and resolving names back
 * to ids at send time — looks simpler and is a trap, because display names are neither unique
 * nor free of spaces, so "@Sam Vimes" cannot be parsed back reliably.
 *
 * The fiddly part is [OffsetMapping]. Every caret position, selection edge and click has to be
 * translated between the two strings, and Compose will throw if the mapping ever reports an
 * offset outside the transformed text. A mention is treated as **atomic**: any original offset
 * inside one maps to the display span's edge, so the caret can sit before or after a mention
 * but never in the middle of an id the user cannot see.
 */
class MentionVisualTransformation(
    private val resolver: MentionResolver,
    private val tint: androidx.compose.ui.graphics.Color,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val segments = mutableListOf<Segment>()
        var cursor = 0
        var shown = 0

        val builder = StringBuilder()
        val spans = mutableListOf<Triple<Int, Int, SpanStyle>>()

        for (match in COMPOSER_MENTION.findAll(raw)) {
            if (match.range.first > cursor) {
                val literal = raw.substring(cursor, match.range.first)
                segments += Segment(cursor, match.range.first, shown, shown + literal.length, false)
                builder.append(literal)
                shown += literal.length
            }

            val display = resolver.displayFor(match.value)
            segments += Segment(
                match.range.first, match.range.last + 1,
                shown, shown + display.length, true,
            )
            builder.append(display)
            spans += Triple(shown, shown + display.length, MENTION_SPAN.copy(background = tint))
            shown += display.length
            cursor = match.range.last + 1
        }
        if (cursor < raw.length) {
            val tail = raw.substring(cursor)
            segments += Segment(cursor, raw.length, shown, shown + tail.length, false)
            builder.append(tail)
            shown += tail.length
        }

        val rendered = buildAnnotatedString {
            append(builder.toString())
            spans.forEach { (from, to, style) -> addStyle(style, from, to) }
        }

        return TransformedText(rendered, SegmentOffsetMapping(segments, raw.length, shown))
    }

    /** One run of the draft, and where it lands in the displayed text. */
    private data class Segment(
        val originalStart: Int,
        val originalEnd: Int,
        val shownStart: Int,
        val shownEnd: Int,
        val isMention: Boolean,
    )

    private class SegmentOffsetMapping(
        private val segments: List<Segment>,
        private val originalLength: Int,
        private val shownLength: Int,
    ) : OffsetMapping {

        override fun originalToTransformed(offset: Int): Int {
            val at = offset.coerceIn(0, originalLength)
            for (s in segments) {
                if (at < s.originalStart) break
                if (at <= s.originalEnd) {
                    // Atomic: an offset inside a mention snaps to whichever end is nearer, so
                    // the caret never lands inside an id that isn't on screen.
                    return if (!s.isMention) s.shownStart + (at - s.originalStart)
                    else if (at - s.originalStart <= s.originalEnd - at) s.shownStart else s.shownEnd
                }
            }
            return shownLength
        }

        override fun transformedToOriginal(offset: Int): Int {
            val at = offset.coerceIn(0, shownLength)
            for (s in segments) {
                if (at < s.shownStart) break
                if (at <= s.shownEnd) {
                    return if (!s.isMention) s.originalStart + (at - s.shownStart)
                    else if (at - s.shownStart <= s.shownEnd - at) s.originalStart else s.originalEnd
                }
            }
            return originalLength
        }
    }

    private companion object {
        /** Same entities the renderer knows, plus the two literals. */
        val COMPOSER_MENTION = Regex("""<@&?\d{1,20}>|<#\d{1,20}>|(?<=^|\s)@(everyone|here)\b""")
        val MENTION_SPAN = SpanStyle(fontWeight = FontWeight.SemiBold)
    }
}
