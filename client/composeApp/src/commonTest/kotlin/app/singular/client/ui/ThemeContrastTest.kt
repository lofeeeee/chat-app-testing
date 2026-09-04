package app.singular.client.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Asserts every authored preset stays readable, in both modes.
 *
 * This test is what allows `ensureContrast` to be retired from the preset path. The previous
 * theme corrected colours at runtime — which kept the app legible but meant the palette you
 * authored was never quite the palette anyone saw, and a bad colour choice surfaced as a silent
 * nudge instead of a reviewable diff. Here a regression fails the build with the exact preset,
 * mode and pair named.
 *
 * The pairs asserted are only the ones the UI actually draws together:
 *
 *  - `text` on `canvas` — the message list, story tray, empty states
 *  - `text` on `raised` — incoming bubbles (raised) with onSurfaceVariant text
 *  - `text` on `surface` — cards, the sidebar, sessions
 *  - `textMuted` on `surface`/`canvas`/`raised` — every secondary label and timestamp
 *  - `onAccent` on `accent` — my bubble text, button labels
 *  - `accentSoft` on `canvas` — author names and the unseen story ring
 *  - `danger` on `canvas` — error banners
 */
class ThemeContrastTest {

    private val aa = 4.5f
    private val aaLarge = 3.0f

    @Test
    fun everyPresetMeetsAA() {
        val failures = mutableListOf<String>()

        for (preset in Presets.all) {
            for (dark in listOf(true, false)) {
                val tag = "${preset.id}/${if (dark) "dark" else "light"}"
                val n = preset.neutrals(dark)
                val a = preset.accent(dark)
                val canvas = composite(n.canvas, a.canvasTint)

                fun check(fg: Color, bg: Color, pair: String, floor: Float = aa) {
                    val ratio = contrastRatio(fg, bg)
                    if (ratio < floor) {
                        failures += "$tag: $pair is ${"%.2f".format(ratio)}:1, needs $floor:1"
                    }
                }

                check(n.text, canvas, "text on canvas")
                check(n.text, n.surface, "text on surface")
                check(n.text, n.raised, "text on raised")
                check(n.textMuted, canvas, "textMuted on canvas")
                check(n.textMuted, n.surface, "textMuted on surface")
                check(n.textMuted, n.raised, "textMuted on raised")
                check(a.onAccent, a.accent, "onAccent on accent")
                check(a.accentSoft, canvas, "accentSoft on canvas")
                check(a.accentSoft, n.surface, "accentSoft on surface")
                check(a.danger, canvas, "danger on canvas")

                // textFaint is placeholder/disabled chrome, not body copy — AA-large, the
                // standard concession for incidental text, is the honest floor.
                check(n.textFaint, canvas, "textFaint on canvas", aaLarge)
            }
        }

        assertTrue(failures.isEmpty(), "Presets failing contrast:\n" + failures.joinToString("\n"))
    }

    @Test
    fun everyPresetHasADistinctId() {
        val ids = Presets.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "Preset ids must be unique — they are the wire format")
    }

    @Test
    fun unknownPresetIdFallsBackToDefault() {
        // The forward-compatibility contract: a newer server can name a preset this build has
        // never heard of, and the client must degrade to the default rather than crash.
        assertEquals(Presets.default, Presets.byId("NO_SUCH_PRESET"))
        assertEquals(Presets.default, Presets.byId(null))
    }

    /** Local copy of the WCAG ratio so the test measures the same maths the runtime uses. */
    private fun contrastRatio(a: Color, b: Color): Float {
        val la = a.luminance() + 0.05f
        val lb = b.luminance() + 0.05f
        return if (la > lb) la / lb else lb / la
    }
}
