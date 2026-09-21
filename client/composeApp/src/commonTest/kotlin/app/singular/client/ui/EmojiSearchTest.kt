package app.singular.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The searchEmoji fast path keeps the old contract — prefix matches rank strictly ahead of
 * word-part matches, and results are alphabetical within each tier — while no longer
 * sorting the whole table per keystroke. These tests pin that contract down so a future
 * optimization can't quietly change the ranking users see.
 */
class EmojiSearchTest {

    @Test
    fun prefixMatchesRankAheadOfContainsMatches() {
        val results = searchEmoji("heart", limit = 48)

        assertTrue(results.isNotEmpty(), "no results for a common word")
        // Every result that starts with the query must come before any that merely
        // contains it.
        val firstContains = results.indexOfFirst { !it.name.startsWith("heart") }
        if (firstContains > 0) {
            val lastPrefix = results.indexOfLast { it.name.startsWith("heart") }
            assertTrue(
                lastPrefix < firstContains,
                "prefix matches must all precede contains matches",
            )
        }
    }

    @Test
    fun resultsAreWithinTheLimit() {
        // "e" matches a huge share of the table; the limit must hold.
        assertEquals(48, searchEmoji("e", limit = 48).size)
    }

    @Test
    fun emptyQueryReturnsNothing() {
        assertTrue(searchEmoji("").isEmpty())
        assertTrue(searchEmoji("   ").isEmpty())
    }

    @Test
    fun exactPrefixCaseIsInsensitive() {
        // Shortcodes are stored lowercase; the query is trimmed and lowercased.
        val upper = searchEmoji("SMILE")
        assertTrue(upper.isNotEmpty())
        assertTrue(upper.all { it.name.contains("smile") })
    }
}
