package app.singular.client.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * The theme: turns a [ThemePreset] into a Material 3 theme the rest of the app can read.
 *
 * ## What changed and why
 *
 * The previous version took two user-picked accent colours and derived a scheme from them, then
 * corrected the result at runtime with `ensureContrast`. Two things made that unsatisfying:
 *
 *  1. **Seven of the eight colour roles the UI actually reads were hardcoded greys**, so the
 *     accent barely reached the screen. Changing it recoloured a bubble and not much else.
 *  2. **A runtime contrast fixer is a confession.** If a palette has to be corrected on the way
 *     to the screen, the palette was never designed. Here every value is authored (see
 *     [Presets]) and correctness is asserted at build time by `ThemeContrastTest`, which fails
 *     the build rather than quietly moving a colour.
 *
 * The fixer is retained as [ensureContrast] for one narrow case only: the legacy fallback path
 * for users who already have raw `themePrimary`/`themeSecondary` saved from the old build. That
 * input genuinely is untrusted user choice, so it still gets corrected. Authored presets do not.
 *
 * ## The extra composition local
 *
 * [SingularColors] carries the roles Material 3 has no slot for — the four-level text ramp and
 * the recessed `sunken` surface. These are exposed as a local rather than smuggled into
 * `colorScheme` under borrowed role names, because a name that lies (`onSurfaceVariant` doing
 * duty for three different emphases) is how the old scheme got muddy in the first place.
 */
@Composable
fun SingularTheme(
    preset: ThemePreset = Presets.default,
    dark: Boolean = isSystemInDarkTheme(),
    /**
     * Raw `0xRRGGBB` accents from before presets existed, used only when the caller has no
     * saved preset. Pass them alongside a resolved [preset] and they win, which is why the
     * caller is responsible for only supplying them when `themePreset` is null.
     */
    legacyPrimary: Int? = null,
    legacySecondary: Int? = null,
    content: @Composable () -> Unit,
) {
    val hasLegacy = legacyPrimary != null || legacySecondary != null

    // Two paths, and they are not symmetric: an authored preset is used exactly as written,
    // while a raw hue the user picked is corrected before it reaches the screen. Only the
    // second is untrusted input.
    val colors = if (hasLegacy) {
        legacyThemeColors(legacyPrimary, legacySecondary, dark)
    } else {
        rememberSingularColors(preset, dark)
    }

    MaterialTheme(
        colorScheme = colors.toColorScheme(dark),
        shapes = SingularShapes,
        content = { ProvideSingularColors(colors) { content() } },
    )
}

// ---------------------------------------------------------------------------
// Extended roles
// ---------------------------------------------------------------------------

/**
 * Everything the UI needs that Material 3 has no slot for.
 *
 * `textFaint` and `sunken` in particular: the old scheme expressed "disabled hint text" and
 * "recessed input well" by reusing `onSurfaceVariant` and `surface`, which meant the same colour
 * was simultaneously a secondary label and a placeholder.
 */
data class SingularColors(
    val canvas: Color,
    val surface: Color,
    val raised: Color,
    val sunken: Color,
    val text: Color,
    val textMuted: Color,
    val textFaint: Color,
    val line: Color,
    val lineStrong: Color,
    val notch: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val danger: Color,
)

@Composable
private fun rememberSingularColors(preset: ThemePreset, dark: Boolean): SingularColors {
    val n = preset.neutrals(dark)
    val a = preset.accent(dark)

    // The tint only touches the canvas. Tinting every surface would multiply the number of
    // colours a reader must hold in their head, and the cast is only perceptible on the largest
    // area anyway.
    val canvas = composite(n.canvas, a.canvasTint)

    return SingularColors(
        canvas = canvas,
        surface = n.surface,
        raised = n.raised,
        sunken = n.sunken,
        text = n.text,
        textMuted = n.textMuted,
        textFaint = n.textFaint,
        line = n.line,
        lineStrong = n.lineStrong,
        notch = n.notch,
        accent = a.accent,
        onAccent = a.onAccent,
        accentSoft = a.accentSoft,
        danger = a.danger,
    )
}

/**
 * Maps [SingularColors] onto the eight Material 3 roles the UI actually reads.
 *
 * The mapping is exhaustive on purpose — including `error`, which the previous scheme left at the
 * Material default and therefore rendered as a red with no relationship to anything else on
 * screen. Every role below is read somewhere in the app; none is filled speculatively.
 *
 * `secondary` is real work here, not filler: the UI reads it as the author-name colour in the
 * compact layout, which was previously `primary` — a colour tuned to be a fill behind white text,
 * doing a text-on-dark job it was never chosen for.
 */
private fun SingularColors.toColorScheme(dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = raised,
        onPrimaryContainer = text,
        secondary = accentSoft,
        onSecondary = canvas,
        background = canvas,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = raised,
        onSurfaceVariant = textMuted,
        outline = line,
        outlineVariant = lineStrong,
        error = danger,
        onError = onAccent,
        errorContainer = danger.copy(alpha = 0.16f),
        onErrorContainer = danger,
        surfaceBright = raised,
        surfaceDim = canvas,
        scrim = canvas.copy(alpha = 0.92f),
    )
} else {
    lightColorScheme(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = raised,
        onPrimaryContainer = text,
        secondary = accentSoft,
        onSecondary = Color.White,
        background = canvas,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = raised,
        onSurfaceVariant = textMuted,
        outline = line,
        outlineVariant = lineStrong,
        error = danger,
        onError = Color.White,
        errorContainer = danger.copy(alpha = 0.12f),
        onErrorContainer = danger,
        surfaceBright = surface,
        surfaceDim = raised,
        scrim = canvas.copy(alpha = 0.92f),
    )
}

val LocalSingularColors = staticCompositionLocalOf {
    SingularColors(
        canvas = Color.Unspecified, surface = Color.Unspecified, raised = Color.Unspecified,
        sunken = Color.Unspecified, text = Color.Unspecified, textMuted = Color.Unspecified,
        textFaint = Color.Unspecified, line = Color.Unspecified, lineStrong = Color.Unspecified,
        notch = Color.Unspecified, accent = Color.Unspecified, onAccent = Color.Unspecified,
        accentSoft = Color.Unspecified, danger = Color.Unspecified,
    )
}

@Composable
private fun ProvideSingularColors(colors: SingularColors, content: @Composable () -> Unit) =
    CompositionLocalProvider(LocalSingularColors provides colors, content = content)

// ---------------------------------------------------------------------------
// Shapes and spacing
// ---------------------------------------------------------------------------

/**
 * One corner language for the whole app.
 *
 * Previously every call site picked its own radius — 16/5 for bubbles, 10 for cards, 12 here, 20
 * there — so the corner radius carried no meaning. Now: the bigger the container, the softer the
 * corner, and [Shapes.extraSmall] is reserved for the *inside* of a run (a bubble continuing the
 * one above it), which is the one place a tight corner is doing real work.
 *
 * Bubble radius goes 16 → 18. At 16 on a 12dp-padded bubble the corner reads as almost-square
 * next to the card chrome; 18 is the value that actually reads as rounded at chat density.
 */
val SingularShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),    // continuation corners inside a bubble run
    small = RoundedCornerShape(10.dp),        // chips, small cards, tooltips
    medium = RoundedCornerShape(14.dp),       // cards
    large = RoundedCornerShape(18.dp),        // bubbles, dialogs
    extraLarge = RoundedCornerShape(24.dp),   // sheets, story media
)

/**
 * The spacing scale.
 *
 * Built from the values already in use — 4, 6, 8, 10, 12, 14, 16, 20, 24, 28 — so that mapping
 * existing literals onto it is 1:1 and visually neutral by construction. Introducing a "nicer"
 * scale that didn't match what's on screen would have meant re-deriving every padding in the app
 * and hoping; this way the only deltas are the ones deliberately chosen.
 */
object Spacing {
    val xs = 4.dp
    val sm = 6.dp
    val md = 8.dp
    val lg = 10.dp
    val xl = 12.dp
    val xxl = 14.dp
    val section = 16.dp
    val block = 20.dp
    val page = 24.dp
    val screen = 28.dp
}

// ---------------------------------------------------------------------------
// Legacy fallback
// ---------------------------------------------------------------------------

/**
 * Builds a scheme the old way, from two raw `0xRRGGBB` accents.
 *
 * Only for users whose saved settings predate presets. It is the one input path that is untrusted
 * user choice rather than an authored palette, so it is also the one path that still gets
 * [ensureContrast] applied. New users never reach it.
 *
 * The neutrals come from the default preset so a legacy user still gets the new warm-editorial
 * chrome; only the accent is theirs.
 */
@Composable
fun legacyThemeColors(primary: Int?, secondary: Int?, dark: Boolean): SingularColors {
    val base = rememberSingularColors(Presets.default, dark)
    if (primary == null && secondary == null) return base

    val against = base.canvas
    val accent = primary?.let { ensureContrast(Color(0xFF000000L.toInt() or it), against) }
        ?: base.accent
    val soft = secondary?.let { ensureContrast(Color(0xFF000000L.toInt() or it), against) }
        ?: accent

    return base.copy(
        accent = accent,
        onAccent = onOf(accent),
        accentSoft = soft,
    )
}

/** Black or white, whichever reads better on [background]. */
fun onOf(background: Color): Color =
    if (contrastRatio(Color.Black, background) >= contrastRatio(Color.White, background)) {
        Color.Black
    } else {
        Color.White
    }

/**
 * Walks [color] toward the opposite end of the luminance range until it clears AA against
 * [against]. Hue is preserved — we're darkening or lightening, not replacing the user's choice.
 *
 * Retained solely for [legacyThemeColors]. Do not call it from a preset path: if an authored
 * colour needs correcting, fix the colour.
 */
fun ensureContrast(color: Color, against: Color, minimum: Float = 4.5f): Color {
    if (contrastRatio(color, against) >= minimum) return color

    val towardBlack = against.luminance() > 0.5f
    var candidate = color

    repeat(20) {
        candidate = if (towardBlack) {
            Color(
                red = candidate.red * 0.92f,
                green = candidate.green * 0.92f,
                blue = candidate.blue * 0.92f,
                alpha = candidate.alpha,
            )
        } else {
            Color(
                red = candidate.red + (1f - candidate.red) * 0.08f,
                green = candidate.green + (1f - candidate.green) * 0.08f,
                blue = candidate.blue + (1f - candidate.blue) * 0.08f,
                alpha = candidate.alpha,
            )
        }
        if (contrastRatio(candidate, against) >= minimum) return candidate
    }
    // Ran out of room — fall back to guaranteed legibility rather than shipping a broken theme.
    return if (towardBlack) Color(0xFF1A1A1A) else Color(0xFFF0F0F0)
}

/** WCAG 2.1 relative-luminance contrast ratio. Ranges 1:1 (identical) to 21:1 (black on white). */
fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/**
 * Deterministic avatar tint from a snowflake, so a user looks the same on every device.
 *
 * Hue is still a hash — that part is load-bearing, it's what makes a given person recognisable
 * across the app — but saturation and lightness are anchored to a fixed, readable band rather
 * than left at arbitrary constants, so an avatar never comes out too pale to see its initial on.
 */
fun avatarColor(userId: String): Color {
    val hash = userId.fold(0) { acc, c -> acc * 31 + c.code }
    val hue = abs(hash % 360).toFloat()
    return Color.hsl(hue, saturation = 0.38f, lightness = 0.52f)
}
