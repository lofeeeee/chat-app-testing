package app.singular.client.ui

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * ZXing's [Encoder] rather than `QRCodeWriter`.
 *
 * The writer scales its output to a requested pixel size, which means reading module boundaries
 * back out of it involves guessing. `Encoder.encode` hands over the module matrix directly, so
 * the drawing code never has to infer where one module ends and the next begins.
 */
actual fun qrMatrix(content: String): Array<BooleanArray> {
    val code = Encoder.encode(
        content,
        // Level M (~15% recovery) is the right trade for a screen-displayed code: L is fragile
        // at an angle, and Q/H inflate the module count enough to hurt on small windows.
        ErrorCorrectionLevel.M,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    val matrix = code.matrix ?: return emptyArray()

    return Array(matrix.height) { y ->
        BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 }
    }
}
