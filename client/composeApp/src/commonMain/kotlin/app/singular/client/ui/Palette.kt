package app.singular.client.ui

import androidx.compose.ui.graphics.Color

/**
 * The theme presets and the colour constants they are built from.
 *
 * ## Why presets instead of two free accent colours
 *
 * The previous design took two user-picked hues and derived everything else. It shipped with a
 * runtime contrast fixer, which is the tell: if a palette needs correcting at runtime, it was
 * never designed — it was guessed. A user picking `primary` has no way to know that the same
 * value also becomes link text, the author-name colour, the focus ring and the progress bar, and
 * that "readable against the chat background" and "readable as a name in the message list" are
 * two different constraints. They pick a colour they like, get an app that is technically WCAG
 * compliant and unpleasant to use, and the fixer quietly moves it anyway.
 *
 * So: **authored palettes, not derived ones.** Five of them, each hand-tuned in both modes, each
 * one a complete answer to "what does the whole app look like".
 *
 * ## Why one shared neutral structure
 *
 * All five presets share [Neutrals] per mode, and only vary [Accent]. That is deliberate rather
 * than lazy. Five fully independent palettes is 120 hand-picked values — and hand-picked
 * neutrals are exactly where unreadable themes come from, because nobody evaluates a surface
 * colour in isolation, they evaluate it in a screenshot. Sharing the structure means the five
 * presets read as one product with five moods, and means a contrast regression fixed in the
 * neutral structure is fixed in all of them at once.
 *
 * The preset still gets a voice: [Accent.canvasTint] is alpha-composited over the shared canvas,
 * so Ember's background is genuinely warm and Slate's genuinely cool, without either of them
 * having an unreviewed bespoke surface ramp.
 *
 * ## Rules for editing this file
 *
 *  - Every value is *authored*. Do not add a `derive`/`ensureContrast` helper here.
 *  - [ThemeContrastTest] asserts AA on the load-bearing pairs in every preset in both modes. It
 *    runs at build time and fails the build. That, not a runtime nudge, is what keeps these
 *    readable.
 *  - Two roles are semantically fixed and must not be re-themed: [Presence] (a legend people
 *    already read without being taught) and [StoryInk] (drawn over arbitrary photos, so it must
 *    stay independent of the surrounding UI).
 *  - Never rename a [ThemePreset.id]. Those strings are persisted server-side in
 *    `user_settings.extras`.
 */
object Presets {

    val Ember = ThemePreset(
        id = "EMBER",
        name = "Ember",
        blurb = "Warm charcoal, amber accent",
        dark = Neutrals.DarkCharcoal,
        light = Neutrals.WarmPaper,
        darkAccent = Accent(
            accent = Color(0xFFE8A33D),
            onAccent = Color(0xFF2A1B06),
            accentSoft = Color(0xFFF0B871),
            danger = Color(0xFFF2706B),
            canvasTint = Color(0x14FF9A3C),
        ),
        lightAccent = Accent(
            accent = Color(0xFFA05F0D),
            onAccent = Color(0xFFFFF7EC),
            accentSoft = Color(0xFF8A5312),
            danger = Color(0xFFC4382F),
            canvasTint = Color(0x0CFF9A3C),
        ),
    )

    val Cocoa = ThemePreset(
        id = "COCOA",
        name = "Cocoa",
        blurb = "Deep espresso, terracotta accent",
        dark = Neutrals.Espresso,
        light = Neutrals.WarmPaper,
        darkAccent = Accent(
            accent = Color(0xFFE08A63),
            onAccent = Color(0xFF2B1305),
            accentSoft = Color(0xFFEFAA88),
            danger = Color(0xFFF07A6E),
            canvasTint = Color(0x16C4632A),
        ),
        lightAccent = Accent(
            accent = Color(0xFFA85A32),
            onAccent = Color(0xFFFFF6F0),
            accentSoft = Color(0xFF7F3F1E),
            danger = Color(0xFFB93A2C),
            canvasTint = Color(0x0CC4632A),
        ),
    )

    val Sage = ThemePreset(
        id = "SAGE",
        name = "Sage",
        blurb = "Green-cast charcoal, olive accent",
        dark = Neutrals.ForestCast,
        light = Neutrals.WarmPaper,
        darkAccent = Accent(
            accent = Color(0xFFA7C47E),
            onAccent = Color(0xFF16210A),
            accentSoft = Color(0xFFC2D8A2),
            danger = Color(0xFFEE7C72),
            canvasTint = Color(0x126FA348),
        ),
        lightAccent = Accent(
            accent = Color(0xFF5C7A37),
            onAccent = Color(0xFFFBFDF4),
            accentSoft = Color(0xFF4A6429),
            danger = Color(0xFFB23B2E),
            canvasTint = Color(0x0C6FA348),
        ),
    )

    val Dusk = ThemePreset(
        id = "DUSK",
        name = "Dusk",
        blurb = "Plum cast, muted rose accent",
        dark = Neutrals.PlumCast,
        light = Neutrals.WarmPaper,
        darkAccent = Accent(
            accent = Color(0xFFD79AC0),
            onAccent = Color(0xFF2B0F22),
            accentSoft = Color(0xFFE7B8D6),
            danger = Color(0xFFF2737A),
            canvasTint = Color(0x14B0589B),
        ),
        lightAccent = Accent(
            accent = Color(0xFF9A4C7C),
            onAccent = Color(0xFFFFF5FA),
            accentSoft = Color(0xFF77365D),
            danger = Color(0xFFB32F45),
            canvasTint = Color(0x0CB0589B),
        ),
    )

    val Slate = ThemePreset(
        id = "SLATE",
        name = "Slate",
        blurb = "Cool charcoal, dusty blue accent",
        dark = Neutrals.CoolCharcoal,
        light = Neutrals.WarmPaper,
        darkAccent = Accent(
            accent = Color(0xFF8FB6E8),
            onAccent = Color(0xFF0C1727),
            accentSoft = Color(0xFFB4CFF3),
            danger = Color(0xFFF07C79),
            canvasTint = Color(0x123E7BC4),
        ),
        lightAccent = Accent(
            accent = Color(0xFF3D6598),
            onAccent = Color(0xFFF4F8FF),
            accentSoft = Color(0xFF2C4C74),
            danger = Color(0xFFB33A32),
            canvasTint = Color(0x0C3E7BC4),
        ),
    )

    val Midnight = ThemePreset(
        id = "MIDNIGHT",
        name = "Midnight",
        blurb = "Deep navy, cyan accent",
        dark = Neutrals.Navy,
        light = Neutrals.CoolPaper,
        darkAccent = Accent(
            accent = Color(0xFF5FD3D8),
            onAccent = Color(0xFF04222A),
            accentSoft = Color(0xFF93E3E6),
            danger = Color(0xFFF07B7B),
            canvasTint = Color(0x162A7FA8),
        ),
        lightAccent = Accent(
            accent = Color(0xFF116B77),
            onAccent = Color(0xFFF0FDFF),
            accentSoft = Color(0xFF0C555F),
            danger = Color(0xFFB4322C),
            canvasTint = Color(0x0C2A7FA8),
        ),
    )

    val Carbon = ThemePreset(
        id = "CARBON",
        name = "Carbon",
        blurb = "Neutral graphite, no cast at all",
        dark = Neutrals.Graphite,
        light = Neutrals.CoolPaper,
        // The one preset with a deliberately colourless accent. Every other theme here has a
        // hue; someone who wants none should not have to pick the least offensive one.
        darkAccent = Accent(
            accent = Color(0xFFD6D9DD),
            onAccent = Color(0xFF16181B),
            accentSoft = Color(0xFFC3C7CC),
            danger = Color(0xFFEF7A76),
            canvasTint = Color(0x00000000),
        ),
        lightAccent = Accent(
            accent = Color(0xFF3A3F45),
            onAccent = Color(0xFFF7F8FA),
            accentSoft = Color(0xFF2D3238),
            danger = Color(0xFFB1352E),
            canvasTint = Color(0x00000000),
        ),
    )

    val Wine = ThemePreset(
        id = "WINE",
        name = "Wine",
        blurb = "Dark burgundy, soft crimson accent",
        dark = Neutrals.Burgundy,
        light = Neutrals.WarmPaper,
        darkAccent = Accent(
            accent = Color(0xFFE58898),
            onAccent = Color(0xFF2E0912),
            accentSoft = Color(0xFFF0AEB8),
            danger = Color(0xFFF4767E),
            canvasTint = Color(0x18B0304C),
        ),
        lightAccent = Accent(
            accent = Color(0xFF9C2F4B),
            onAccent = Color(0xFFFFF4F6),
            accentSoft = Color(0xFF7C2039),
            danger = Color(0xFFB3302A),
            canvasTint = Color(0x0CB0304C),
        ),
    )

    /**
     * Light-first presets.
     *
     * They still author a dark half — the mode switch has to work from anywhere, and a preset
     * that only looked right in one mode would be a trap. What makes them "light-first" is that
     * the light side is where they were designed and where their character lives.
     */
    val Paper = ThemePreset(
        id = "PAPER",
        name = "Paper",
        blurb = "Warm paper, ink accent — designed light",
        dark = Neutrals.DarkCharcoal,
        light = Neutrals.WarmPaper,
        darkAccent = Accent(
            accent = Color(0xFFDCCFBB),
            onAccent = Color(0xFF1E1913),
            accentSoft = Color(0xFFCDBFA9),
            danger = Color(0xFFF07A73),
            canvasTint = Color(0x0EC9A87A),
        ),
        lightAccent = Accent(
            accent = Color(0xFF3B342A),
            onAccent = Color(0xFFFBF8F2),
            accentSoft = Color(0xFF2C261E),
            danger = Color(0xFFB0362C),
            canvasTint = Color(0x00000000),
        ),
    )

    val Frost = ThemePreset(
        id = "FROST",
        name = "Frost",
        blurb = "Cool white, glacier blue — designed light",
        dark = Neutrals.CoolCharcoal,
        light = Neutrals.CoolPaper,
        darkAccent = Accent(
            accent = Color(0xFF9CC7EE),
            onAccent = Color(0xFF0B1A28),
            accentSoft = Color(0xFFBBDAF6),
            danger = Color(0xFFF07C79),
            canvasTint = Color(0x123E7BC4),
        ),
        lightAccent = Accent(
            accent = Color(0xFF2F6091),
            onAccent = Color(0xFFF3F8FE),
            accentSoft = Color(0xFF24496E),
            danger = Color(0xFFAF352D),
            canvasTint = Color(0x0A4C8FD0),
        ),
    )

    /**
     * Shipped order — the order the picker shows them in.
     *
     * Dark-designed presets first, light-designed last, because that is the split most people
     * are choosing between and grouping it is cheaper than labelling it.
     */
    val all = listOf(Ember, Cocoa, Sage, Dusk, Slate, Midnight, Carbon, Wine, Paper, Frost)

    /** The presets whose character lives in light mode. Used to caption the picker. */
    val lightFirst = setOf(Paper.id, Frost.id)

    val default: ThemePreset get() = Ember

    /**
     * Looks a preset up by its persisted [ThemePreset.id].
     *
     * Unknown ids fall back to [default] rather than throwing: a newer server can ship a preset
     * this build has never heard of, and the correct response to that is "show the default
     * theme", not "crash on launch". This is the forward-compatibility half of persisting an
     * enum as a string.
     */
    fun byId(id: String?): ThemePreset = all.firstOrNull { it.id == id } ?: default
}

/**
 * The neutral structure of the interface: everything that isn't the accent.
 *
 * Four levels of text and two of border, because the previous scheme's single `onSurfaceVariant`
 * was doing the work of three different emphases (secondary label, tertiary hint, and disabled)
 * and therefore lied about all of them.
 *
 * Named by *job*, not by Material role. `raised` rather than `surfaceVariant` — several early
 * readers of this file guessed `surfaceVariant` was a brighter surface, and it isn't; naming the
 * job removes the guess.
 */
data class Neutrals(
    /** The outermost canvas: behind the sidebar, behind the message list. Lowest of the set. */
    val canvas: Color,
    /** Sidebar and cards. Rests on [canvas]. */
    val surface: Color,
    /** Incoming bubbles, chips, selected rows. Rests on [surface]. */
    val raised: Color,
    /** Input wells and other recessed things. Darker than [surface], not lighter. */
    val sunken: Color,
    /** Primary body text. */
    val text: Color,
    /** Secondary labels — channel subtitles, timestamps. */
    val textMuted: Color,
    /** Tertiary hints, placeholder text, disabled. */
    val textFaint: Color,
    /** Hairlines: dividers, unfocused outlines. */
    val line: Color,
    /** Emphasis borders: the selected layout card, a focused input. */
    val lineStrong: Color,
    /** The notch between an avatar and its presence dot. Opaque, so it masks the avatar. */
    val notch: Color,
) {
    companion object {

        /**
         * The shared dark canvas family — warm, and deliberately never pure black.
         *
         * `#000` gives unbounded contrast against white text, which sounds like a win and reads
         * as a defect: halation on light glyphs, and no room left to express elevation. Every
         * surface here sits in a narrow band above black and the elevation story is carried by
         * *hue warmth and border lightness* rather than by lightness alone, which is what makes
         * the layering visible without a shadow.
         */
        val DarkCharcoal = Neutrals(
            canvas = Color(0xFF14110F),
            surface = Color(0xFF1C1815),
            raised = Color(0xFF272220),
            sunken = Color(0xFF100E0C),
            text = Color(0xFFF5EFE7),
            textMuted = Color(0xFFB4A99E),
            textFaint = Color(0xFF7C7268),
            line = Color(0xFF332C27),
            lineStrong = Color(0xFF574B42),
            notch = Color(0xFF1C1815),
        )

        val Espresso = DarkCharcoal.copy(
            canvas = Color(0xFF17120E),
            surface = Color(0xFF201911),
            raised = Color(0xFF2C2318),
            sunken = Color(0xFF120E0A),
            text = Color(0xFFF7F0E4),
            line = Color(0xFF382D22),
            lineStrong = Color(0xFF5E4B39),
            notch = Color(0xFF201911),
        )

        val ForestCast = DarkCharcoal.copy(
            canvas = Color(0xFF101310),
            surface = Color(0xFF171B17),
            raised = Color(0xFF212622),
            sunken = Color(0xFF0C0F0C),
            text = Color(0xFFEDF2EA),
            textMuted = Color(0xFFA7B3A3),
            textFaint = Color(0xFF747F71),
            line = Color(0xFF2A302A),
            lineStrong = Color(0xFF465044),
            notch = Color(0xFF171B17),
        )

        val PlumCast = DarkCharcoal.copy(
            canvas = Color(0xFF15111A),
            surface = Color(0xFF1D1724),
            raised = Color(0xFF282032),
            sunken = Color(0xFF110D15),
            text = Color(0xFFF4EEF8),
            textMuted = Color(0xFFB2A6BC),
            textFaint = Color(0xFF7D7189),
            line = Color(0xFF342A40),
            lineStrong = Color(0xFF584669),
            notch = Color(0xFF1D1724),
        )

        val CoolCharcoal = DarkCharcoal.copy(
            canvas = Color(0xFF0F1216),
            surface = Color(0xFF161A20),
            raised = Color(0xFF20262E),
            sunken = Color(0xFF0B0E11),
            text = Color(0xFFE9EEF5),
            textMuted = Color(0xFFA2AEBD),
            textFaint = Color(0xFF6F7B8A),
            line = Color(0xFF28303A),
            lineStrong = Color(0xFF445062),
            notch = Color(0xFF161A20),
        )

        val Navy = DarkCharcoal.copy(
            canvas = Color(0xFF0B1220),
            surface = Color(0xFF111A2B),
            raised = Color(0xFF1A2639),
            sunken = Color(0xFF080D18),
            text = Color(0xFFE6EDF7),
            textMuted = Color(0xFF9FAFC6),
            textFaint = Color(0xFF6C7D95),
            line = Color(0xFF1F2C41),
            lineStrong = Color(0xFF3B4E6C),
            notch = Color(0xFF111A2B),
        )

        /**
         * A true neutral, with the hue deliberately at zero.
         *
         * Every other dark family here carries a cast, because a cast is what stops a dark
         * canvas reading as dead. This one is for people who read that as noise — so it
         * compensates with a slightly wider lightness spread between levels instead, which is
         * the only tool left for showing elevation once hue is off the table.
         */
        val Graphite = DarkCharcoal.copy(
            canvas = Color(0xFF0E0F11),
            surface = Color(0xFF17191C),
            raised = Color(0xFF24272B),
            sunken = Color(0xFF08090A),
            text = Color(0xFFECEEF1),
            textMuted = Color(0xFFA6ABB2),
            textFaint = Color(0xFF737981),
            line = Color(0xFF262A2F),
            lineStrong = Color(0xFF474D55),
            notch = Color(0xFF17191C),
        )

        val Burgundy = DarkCharcoal.copy(
            canvas = Color(0xFF170D11),
            surface = Color(0xFF20131A),
            raised = Color(0xFF2D1B24),
            sunken = Color(0xFF120A0D),
            text = Color(0xFFF7EAEE),
            textMuted = Color(0xFFBCA3AC),
            textFaint = Color(0xFF876E77),
            line = Color(0xFF37222C),
            lineStrong = Color(0xFF5E3A48),
            notch = Color(0xFF20131A),
        )

        /**
         * The cool light side — for the presets whose accent is blue, cyan or colourless.
         *
         * A second light family rather than a fifth reason to reuse [WarmPaper]. The note there
         * about light-mode tints reading as stain is still true, and this is not a tinted
         * paper: it is a *neutral* paper on the cool side of grey, so a glacier-blue accent
         * doesn't sit on a background that is quietly fighting it. Two authored papers is a
         * different thing from ten tinted ones.
         */
        val CoolPaper = Neutrals(
            canvas = Color(0xFFF2F4F7),
            surface = Color(0xFFFFFFFF),
            raised = Color(0xFFE7EBF0),
            sunken = Color(0xFFE3E8EE),
            text = Color(0xFF161A1F),
            textMuted = Color(0xFF5C646E),
            textFaint = Color(0xFF7E8792),
            line = Color(0xFFDCE1E8),
            lineStrong = Color(0xFFA8B2BE),
            notch = Color(0xFFFFFFFF),
        )

        /**
         * The shared light side: warm paper, ink text.
         *
         * One warm-paper neutral for all five presets rather than five tinted papers. On the
         * dark side a tint is what stops the canvas reading as dead grey; on the light side the
         * same trick just makes some presets look like a dirty version of the others, because
         * light-mode tints are far more visible as *stain*. Presets differentiate on the light
         * side through the accent alone.
         */
        val WarmPaper = Neutrals(
            canvas = Color(0xFFF7F3EC),
            surface = Color(0xFFFFFCF7),
            raised = Color(0xFFF0EAE0),
            sunken = Color(0xFFEDE6DB),
            text = Color(0xFF221C15),
            textMuted = Color(0xFF6A6055),
            // 3.5:1 on canvas — AA-large, the floor for placeholder and disabled chrome. A
            // value lighter than this stops reading as "de-emphasised" and starts reading as
            // "broken screen", which ThemeContrastTest is there to catch.
            textFaint = Color(0xFF8A8073),
            line = Color(0xFFE0D8CC),
            lineStrong = Color(0xFFB5A895),
            notch = Color(0xFFFFFCF7),
        )
    }
}

/**
 * The part of a theme that actually changes between presets.
 *
 * [accent] and [accentSoft] are two different colours on purpose, and the distinction is the
 * whole reason the old "one primary" model produced muddy interfaces: a colour that is legible
 * *as a fill behind white text* is too dark to be legible *as text on a dark canvas*. The old
 * scheme used `primary` for both my-bubble fill and author names, so those two uses were always
 * in direct competition and one of them always lost.
 */
data class Accent(
    /** Fills. My bubbles, buttons, active indicators. Paired with [onAccent]. */
    val accent: Color,
    /** Text and icons drawn on top of [accent]. */
    val onAccent: Color,
    /** The accent used *as text* — author names, mentions, links. Legible on every surface. */
    val accentSoft: Color,
    /** Error text. In-family, so a red banner doesn't look borrowed from another app. */
    val danger: Color,
    /**
     * Alpha-composited over [Neutrals.canvas] to give the preset its cast.
     *
     * Applied to the canvas only — tinting every surface would multiply the number of colours
     * a reader has to hold in their head for no visible gain, since the cast is only really
     * perceptible on the largest area.
     */
    val canvasTint: Color,
)

/** A complete, authored theme. [id] is the wire value and is persisted server-side. */
data class ThemePreset(
    val id: String,
    val name: String,
    val blurb: String,
    val dark: Neutrals,
    val light: Neutrals,
    val darkAccent: Accent,
    val lightAccent: Accent,
) {
    /**
     * The neutral half for a mode.
     *
     * Parameter is `isDark` and not `dark`: this class already has a `dark: Neutrals` property,
     * and a parameter named the same shadows it — turning `if (dark) dark else light` into
     * "if (Boolean) Boolean else Neutrals" and the whole thing into `Any`. Easy to write, and
     * the compiler is the only thing that notices.
     */
    fun neutrals(isDark: Boolean): Neutrals = if (isDark) dark else light

    /** Likewise the accent half. */
    fun accent(isDark: Boolean): Accent = if (isDark) darkAccent else lightAccent
}

/**
 * Presence colours.
 *
 * Semantically fixed, and intentionally not part of [ThemePreset]: these are a legend. Someone
 * who has used any chat app reads green-online / amber-away / red-busy without being taught, and
 * making them follow the accent would trade a convention that works for a coherence nobody asked
 * for. The only concession to the theme is [ring], which has to match whatever is behind the dot.
 */
object Presence {
    val online = Color(0xFF23A55A)
    val away = Color(0xFFF0B232)
    val dnd = Color(0xFFF23F43)
    val offline = Color(0xFF80848E)

    fun statusColor(status: String): Color = when (status) {
        "ONLINE" -> online
        "AWAY" -> away
        "DND" -> dnd
        else -> offline   // OFFLINE, and INVISIBLE as everyone else sees it
    }
}

/**
 * Story caption colours.
 *
 * Also not theme-derived, for a different reason than [Presence]: these are drawn over arbitrary
 * user photos, so any relationship to the surrounding UI is coincidental and any attempt to keep
 * them "in family" would make them unreadable over some photo. Fixed values, chosen for legibility
 * over an unknown background.
 *
 * They live here rather than in [StoryComposer] because three files previously carried their own
 * copy of this list.
 */
val StoryInk = listOf(
    "#FFFFFF",
    "#F23F43",
    "#F0B232",
    "#23A55A",
    "#3D5AFE",
    "#B57EDC",
)

/**
 * Mixes [overlay] over [base] in sRGB.
 *
 * Used for [Accent.canvasTint]. `lerp` is the wrong tool for a translucent tint despite looking
 * right: it needs a fraction, not a colour-with-alpha, so the tint's alpha would have to be
 * unpacked and re-passed at every call site — and every future caller would get it subtly wrong.
 * Taking a `Color` and honouring its own alpha keeps the tint self-describing.
 */
internal fun composite(base: Color, overlay: Color): Color = Color(
    red = overlay.red * overlay.alpha + base.red * (1f - overlay.alpha),
    green = overlay.green * overlay.alpha + base.green * (1f - overlay.alpha),
    blue = overlay.blue * overlay.alpha + base.blue * (1f - overlay.alpha),
    alpha = base.alpha,
)
