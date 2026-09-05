package app.singular.client.ui

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

    if (isGuildChannel && q.isNotEmpty()) {
        roles.filter { it.mentionable && it.name.lowercase().contains(q) && !it.isDefault }
            .take(3)
            .forEach { out += MentionCandidate.Role(it.id, it.name) }
    }

    return out.take(limit + 2)
}
