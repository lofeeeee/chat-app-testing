package app.singular.client.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Backgrounds for a text-only story.
 *
 * Authored, like the themes, and for the same reason: a free colour picker lets someone choose
 * a background their own white caption is invisible against, and the app then has to either
 * override their choice or let them post something unreadable. A short list of designed
 * gradients removes the failure mode instead of correcting it afterwards.
 *
 * Every one of these is dark enough that white text sits on it comfortably, which is what lets
 * the editor default the ink to white and be right almost always.
 *
 * **Never rename an [id].** It is persisted as `Story.background` on the server, so a renamed
 * id turns every existing story using it into the fallback.
 */
data class StoryBackground(
    val id: String,
    val name: String,
    val colors: List<Color>,
) {
    /**
     * A diagonal sweep, top-left to bottom-right.
     *
     * Diagonal rather than vertical because a story frame is tall and narrow: a vertical
     * gradient across that much height reads as two flat colours with a seam, while a diagonal
     * keeps a visible transition through the middle where the text actually is.
     *
     * A single-colour background yields a flat brush, so solids and gradients are one type
     * rather than two with a branch at every call site.
     */
    val brush: Brush
        get() = if (colors.size == 1) {
            Brush.linearGradient(listOf(colors[0], colors[0]))
        } else {
            Brush.linearGradient(
                colors = colors,
                start = Offset.Zero,
                end = Offset.Infinite,
            )
        }
}

object StoryBackgrounds {

    val Midnight = StoryBackground(
        "MIDNIGHT", "Midnight",
        listOf(Color(0xFF1B2A4A), Color(0xFF0B1120)),
    )
    val Sunset = StoryBackground(
        "SUNSET", "Sunset",
        listOf(Color(0xFFB2452F), Color(0xFF6A1E45)),
    )
    val Forest = StoryBackground(
        "FOREST", "Forest",
        listOf(Color(0xFF1E3B2C), Color(0xFF0C1A14)),
    )
    val Plum = StoryBackground(
        "PLUM", "Plum",
        listOf(Color(0xFF4A2251), Color(0xFF1B0E24)),
    )
    val Ember = StoryBackground(
        "EMBER", "Ember",
        listOf(Color(0xFF7A3B10), Color(0xFF2A1206)),
    )
    val Ocean = StoryBackground(
        "OCEAN", "Ocean",
        listOf(Color(0xFF10454F), Color(0xFF06212A)),
    )
    val Ink = StoryBackground(
        "INK", "Ink",
        listOf(Color(0xFF1A1A1D)),
    )
    val Rose = StoryBackground(
        "ROSE", "Rose",
        listOf(Color(0xFF8E3A5B), Color(0xFF3A1226)),
    )

    val all = listOf(Midnight, Sunset, Forest, Plum, Ember, Ocean, Ink, Rose)

    val default: StoryBackground get() = Midnight

    /**
     * Looks one up by its persisted id.
     *
     * Unknown ids fall back to [default] rather than throwing — a story written by a newer
     * client naming a background this build doesn't have should still be readable, just on a
     * different ground. Same contract as [Presets.byId].
     */
    fun byId(id: String?): StoryBackground = all.firstOrNull { it.id == id } ?: default
}
