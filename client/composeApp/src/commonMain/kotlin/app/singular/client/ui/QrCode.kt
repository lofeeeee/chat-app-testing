package app.singular.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.floor

/**
 * Encodes [content] into a QR module matrix.
 *
 * Returns the raw modules rather than a bitmap so the code can be drawn with Compose at any
 * density and take its colours from the theme — a pre-rendered black-on-white PNG looks wrong
 * on a dark background and blurs when scaled.
 */
expect fun qrMatrix(content: String): Array<BooleanArray>

/**
 * Draws a QR code.
 *
 * The quiet zone matters: the spec requires four clear modules on every side, and scanners
 * genuinely fail without it. It's drawn here rather than left to the caller's padding, because
 * "the QR sometimes doesn't scan" is a miserable bug to track down.
 */
@Composable
fun QrCode(
    content: String,
    modifier: Modifier = Modifier,
    size: Dp = 240.dp,
    quietZoneModules: Int = 4,
) {
    val matrix = remember(content) { runCatching { qrMatrix(content) }.getOrNull() }
    val foreground = MaterialTheme.colorScheme.onSurface
    val background = MaterialTheme.colorScheme.surface

    Box(modifier.size(size)) {
        if (matrix == null || matrix.isEmpty()) return@Box

        Canvas(Modifier.size(size)) {
            val modules = matrix.size + quietZoneModules * 2
            // Floor the module size so rounding never accumulates into a half-pixel seam
            // between modules, which scanners read as noise.
            val module = floor(this.size.minDimension / modules)
            val drawn = module * modules
            val originX = (this.size.width - drawn) / 2f
            val originY = (this.size.height - drawn) / 2f

            drawRect(color = background, topLeft = Offset(originX, originY),
                size = Size(drawn, drawn))

            for (y in matrix.indices) {
                for (x in matrix[y].indices) {
                    if (!matrix[y][x]) continue
                    drawRect(
                        color = foreground,
                        topLeft = Offset(
                            originX + (x + quietZoneModules) * module,
                            originY + (y + quietZoneModules) * module,
                        ),
                        // A hairline overdraw closes sub-pixel gaps between adjacent modules.
                        size = Size(module + 0.5f, module + 0.5f),
                    )
                }
            }
        }
    }
}
