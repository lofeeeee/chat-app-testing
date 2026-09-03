package app.singular.client.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.abs

/**
 * User-customisable accent colours (feature 16).
 *
 * The user picks two colours; everything else is derived. Two rules make that safe:
 *
 *  1. **Foreground colours are computed, never picked.** [onOf] chooses black or white by
 *     contrast ratio, so a user who selects a pale primary still gets readable label text.
 *  2. **Contrast has a floor.** [ensureContrast] nudges an accent away from the background
 *     until it clears WCAG AA (4.5:1). Without it, users build unreadable themes and then file
 *     bugs about the app being broken.
 *
 * A full Material 3 tonal ramp (HCT) is the eventual answer and is a vendorable library — this
 * is the smaller version that covers the phase-1 surface.
 */
object SingularPalette {
    val DefaultPrimary = Color(0xFF3D5AFE)
    val DefaultSecondary = Color(0xFF00BFA5)
}

@Composable
fun SingularTheme(
    primary: Color = SingularPalette.DefaultPrimary,
    secondary: Color = SingularPalette.DefaultSecondary,
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val background = if (dark) Color(0xFF101318) else Color(0xFFF6F7F9)
    val surface = if (dark) Color(0xFF171B22) else Color.White

    val safePrimary = ensureContrast(primary, background)
    val safeSecondary = ensureContrast(secondary, background)

    val scheme = if (dark) {
        darkColorScheme(
            primary = safePrimary,
            onPrimary = onOf(safePrimary),
            secondary = safeSecondary,
            onSecondary = onOf(safeSecondary),
            background = background,
            onBackground = Color(0xFFE6E9ED),
            surface = surface,
            onSurface = Color(0xFFE6E9ED),
            surfaceVariant = Color(0xFF242A34),
            onSurfaceVariant = Color(0xFFA3ADBA),
            outline = Color(0xFF3A424E),
        )
    } else {
        lightColorScheme(
            primary = safePrimary,
            onPrimary = onOf(safePrimary),
            secondary = safeSecondary,
            onSecondary = onOf(safeSecondary),
            background = background,
            onBackground = Color(0xFF171B21),
            surface = surface,
            onSurface = Color(0xFF171B21),
            surfaceVariant = Color(0xFFE1E5EB),
            onSurfaceVariant = Color(0xFF4A5462),
            outline = Color(0xFFB8BFC9),
        )
    }

    MaterialTheme(colorScheme = scheme, content = content)
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

/** Deterministic avatar tint from a snowflake, so a user looks the same on every device. */
fun avatarColor(userId: String): Color {
    val hash = userId.fold(0) { acc, c -> acc * 31 + c.code }
    val hue = abs(hash % 360).toFloat()
    return Color.hsl(hue, saturation = 0.45f, lightness = 0.55f)
}
