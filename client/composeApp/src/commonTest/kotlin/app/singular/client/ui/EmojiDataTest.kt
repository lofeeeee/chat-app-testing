package app.singular.client.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Guards the emoji tables against static-initialisation order.
 *
 * This exists because of a real crash. [ALL_EMOJI] is declared above the category lists it
 * reads, and Kotlin initialises a file's top-level properties in declaration order — so an
 * eager initialiser saw them as null, `flatMap` handed `addAll` a null, and the class failed to
 * initialise. Nothing about that is visible at compile time: it surfaced as
 * `ExceptionInInitializerError` the first time anyone opened the emoji picker.
 *
 * Simply *touching* these properties is the assertion. A test that reads them would have failed
 * on the broken build, which is the whole point — the counts below are secondary.
 */
class EmojiDataTest {

    @Test
    fun tablesInitialiseWithoutOrderingFaults() {
        // Reading ALL_EMOJI triggers the class initialiser. On the broken version this threw
        // before reaching any assertion.
        val all = ALL_EMOJI
        assertTrue(all.isNotEmpty(), "ALL_EMOJI is empty — a category list initialised as null")

        // Every category must contribute. A null list would silently flatten to nothing, so
        // checking the total alone could still pass with one whole category missing.
        for (category in EmojiCategory.entries) {
            val entries = emojiFor(category)
            if (category == EmojiCategory.RECENT) {
                assertEquals(0, entries.size, "RECENT is populated at runtime, not statically")
            } else {
                assertTrue(entries.isNotEmpty(), "$category contributed no emoji")
            }
        }
    }

    @Test
    fun everyEntryIsUsable() {
        assertTrue(ALL_EMOJI.none { it.emoji.isBlank() }, "an entry has no glyph")
        assertTrue(ALL_EMOJI.none { it.name.isBlank() }, "an entry has no shortcode")
        assertEquals(
            ALL_EMOJI.size,
            ALL_EMOJI.distinctBy { it.name }.size,
            "duplicate shortcodes — EMOJI_BY_NAME would silently drop one",
        )
    }

    @Test
    fun lookupsAndSearchWork() {
        assertTrue(EMOJI_BY_NAME.isNotEmpty())
        assertEquals(ALL_EMOJI.size, EMOJI_BY_NAME.size)

        // A prefix match must rank ahead of a mid-word one.
        val results = searchEmoji("smi")
        assertTrue(results.isNotEmpty(), "search found nothing for a common prefix")
        assertTrue(results.first().name.startsWith("smi"), "prefix matches should rank first")

        assertTrue(searchEmoji("").isEmpty(), "an empty query should not return the whole table")
    }

    @Test
    fun quickReactionsAreRealEmoji() {
        assertTrue(QUICK_REACTIONS.isNotEmpty())
        assertTrue(QUICK_REACTIONS.none { it.isBlank() })
    }
}
