package app.singular.client.platform

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Android crop.
 *
 * `inJustDecodeBounds` first, then `inSampleSize`, so a 12-megapixel camera photo is never
 * fully decoded just to keep a 512px square out of it — that is the allocation that ends in an
 * OutOfMemoryError on a mid-range phone. Sampling is powers of two only, so it is deliberately
 * conservative: decode no smaller than twice the output, then scale precisely from there.
 */
actual fun cropSquare(
    source: ByteArray,
    originX: Float,
    originY: Float,
    size: Float,
    outputPx: Int,
): ByteArray? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(source, 0, source.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

    val shortEdge = minOf(bounds.outWidth, bounds.outHeight)
    val wantedSourcePx = (size * shortEdge).coerceAtLeast(1f)

    var sample = 1
    while (wantedSourcePx / (sample * 2) >= outputPx * 2) sample *= 2

    val decoded = BitmapFactory.decodeByteArray(
        source, 0, source.size,
        BitmapFactory.Options().apply { inSampleSize = sample },
    ) ?: return null

    val shortDecoded = minOf(decoded.width, decoded.height)
    val side = (size * shortDecoded).roundToInt().coerceIn(1, shortDecoded)
    val x = (originX * decoded.width).roundToInt().coerceIn(0, decoded.width - side)
    val y = (originY * decoded.height).roundToInt().coerceIn(0, decoded.height - side)

    val cropped = Bitmap.createBitmap(decoded, x, y, side, side)
    // Never upscale — see the desktop actual.
    val edge = minOf(outputPx, side)
    val scaled =
        if (edge == side) cropped
        else Bitmap.createScaledBitmap(cropped, edge, edge, true)

    ByteArrayOutputStream().use { sink ->
        scaled.compress(Bitmap.CompressFormat.PNG, 100, sink)
        sink.toByteArray()
    }
}.getOrNull()
