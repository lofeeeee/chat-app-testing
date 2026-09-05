package app.singular.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.jetbrains.compose.resources.Font
import singular_client.composeapp.generated.resources.Res
import singular_client.composeapp.generated.resources.font

/**
 * The bundled emoji face (what.txt: never rely on the OS emoji font — Windows renders many
 * glyphs badly and every platform draws a different set). One colour TTF covering the full
 * Unicode set; no per-emoji assets, no runtime fetch.
 *
 * Compose has no automatic custom-font fallback, so this is applied per-run: [emojiRunRanges]
 * splits a string into the spans an emoji font should claim, and the renderers wrap those
 * spans in a `SpanStyle(fontFamily = …)` resolved from here.
 */
@Composable
fun emojiFontFamily(): FontFamily =
    FontFamily(Font(Res.font.noto_color_emoji, FontWeight.Normal, FontStyle.Normal))

/**
 * Codepoints the emoji font should claim.
 *
 * Deliberately broad on the supplementary plane (≥ U+1F000): text there is vanishingly rare
 * in a chat message and every emoji added since 2015 lives in it. On the BMP, only the
 * established emoji blocks — claiming all of `2XXX` would route arrows and math to a font
 * that has no glyphs for half of them.
 */
private fun isEmojiCodePoint(c: Int): Boolean = when {
    c >= 0x1F000 -> true
    c in 0x2600..0x27BF -> true            // Misc Symbols, Dingbats — nearly all emoji
    c in 0x2B00..0x2BFF -> true            // ⭐ ⬛ ⬜
    c == 0x203C || c == 0x2049 -> true     // ‼ ⁉
    c == 0x2122 || c == 0x2139 -> true     // ℹ
    c == 0x24C2 -> true                    // Ⓜ
    c in 0x25AA..0x25FE -> true            // ▪ ▫ ▶ ◀ …
    c == 0x2934 || c == 0x2935 -> true     // ⤴ ⤵
    c == 0x3030 || c == 0x303D -> true
    c == 0x3297 || c == 0x3299 -> true
    c == 0x2764 -> true                    // ♥ (heart, usually followed by VS16)
    else -> false
}

/** Joiner and selector codepoints: never claimable alone, but continue a run. */
private fun isSequenceContinuation(c: Int): Boolean =
    c == 0xFE0F || c == 0x200D || c == 0x20E3 || (c in 0xE0020..0xE007F) // tag chars

/**
 * The character ranges of [text] that should render in the emoji font.
 *
 * A run is maximal: it starts at a claimable codepoint and absorbs continuation codepoints
 * (variation selectors, ZWJ, enclosing keycap) and further emoji codepoints, so a skin-toned
 * ZWJ family sequence stays one run rendered by one font.
 *
 * Keycaps (`1️⃣` = '1' + VS16 + keycap) are the one backward case: the leading ASCII digit
 * must join the run or the glyph breaks in half, so a run that begins with a continuation
 * codepoint swallows one preceding digit/#/*.
 */
fun emojiRunRanges(text: String): List<IntRange> {
    val ranges = mutableListOf<IntRange>()
    var i = 0
    while (i < text.length) {
        val c = text[i].code
        val isFlag = c in 0x1F1E6..0x1F1FF   // regional indicators
        if (!isEmojiCodePoint(c) && !isSequenceContinuation(c) && !isFlag) { i++; continue }

        val start = i
        while (i < text.length) {
            val ci = text[i].code
            if (isEmojiCodePoint(ci) || isSequenceContinuation(ci) ||
                ci in 0x1F1E6..0x1F1FF) {
                i++
            } else break
        }

        var effectiveStart = start
        // A keycap's base character is an ASCII digit/#/* immediately before the selector.
        if (effectiveStart > 0 && effectiveStart < text.length) {
            val base = text[effectiveStart - 1]
            if (isSequenceContinuation(text[effectiveStart].code) &&
                (base.isDigit() || base == '#' || base == '*')) {
                effectiveStart--
            }
        }

        ranges += effectiveStart until i
    }
    return ranges
}
