package app.singular.client.ui

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/** Identical to the desktop actual — `com.google.zxing:core` is pure Java with no Android deps. */
actual fun qrMatrix(content: String): Array<BooleanArray> {
    val code = Encoder.encode(
        content,
        ErrorCorrectionLevel.M,
        mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
    )
    val matrix = code.matrix ?: return emptyArray()

    return Array(matrix.height) { y ->
        BooleanArray(matrix.width) { x -> matrix.get(x, y).toInt() == 1 }
    }
}
