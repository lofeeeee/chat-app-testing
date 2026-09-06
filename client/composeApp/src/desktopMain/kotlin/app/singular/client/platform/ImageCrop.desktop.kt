package app.singular.client.platform

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.roundToInt

/**
 * Desktop crop, through ImageIO — already on the classpath, no dependency.
 *
 * PNG out, always. JPEG would be smaller, but re-encoding a JPEG crop means a second round of
 * lossy compression on top of whatever the camera already did, and an avatar is small enough
 * that the size difference is not worth the artefacts. PNG also keeps transparency, which a
 * cropped logo may well have.
 */
actual fun cropSquare(
    source: ByteArray,
    originX: Float,
    originY: Float,
    size: Float,
    outputPx: Int,
): ByteArray? = runCatching {
    val image = ImageIO.read(ByteArrayInputStream(source)) ?: return null

    // The selection is a fraction of the *shorter* edge, so a square crop stays square on a
    // portrait or landscape source without the caller doing aspect maths.
    val shortEdge = minOf(image.width, image.height)
    val side = (size * shortEdge).roundToInt().coerceIn(1, shortEdge)

    // Clamped so a drag that ran past the edge yields the nearest valid square rather than an
    // exception. The editor already limits panning; this is the backstop.
    val x = (originX * image.width).roundToInt().coerceIn(0, image.width - side)
    val y = (originY * image.height).roundToInt().coerceIn(0, image.height - side)

    val cropped = image.getSubimage(x, y, side, side)

    // Never upscale: enlarging a 96px avatar to 512 stores four times the bytes for the same
    // detail, and looks softer than the original for it.
    val edge = minOf(outputPx, side)
    val out = BufferedImage(edge, edge, BufferedImage.TYPE_INT_ARGB)
    val g = out.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.drawImage(cropped, 0, 0, edge, edge, null)
    g.dispose()

    ByteArrayOutputStream().use { sink ->
        if (!ImageIO.write(out, "png", sink)) return null
        sink.toByteArray()
    }
}.getOrNull()
