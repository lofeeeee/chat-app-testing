package app.singular.client.platform

/**
 * Cuts a square out of an image and re-encodes it.
 *
 * Cropping has to happen **before** the upload, not as a display trick. A circular mask in the
 * UI would leave the full picture on the server — every other client would show the uncropped
 * original, and anyone with the URL could see what was cropped out, which people reasonably
 * assume is gone. So the bytes that leave the machine are the bytes that were kept.
 *
 * The crop is expressed in **fractions of the source**, not pixels: the editor works in a
 * viewport whose size depends on the window, and it must not need to know the source's real
 * dimensions to describe a selection.
 *
 * @param originX  left edge of the crop, 0..1 of the source width
 * @param originY  top edge, 0..1 of the source height
 * @param size     side length, as a fraction of the **shorter** source edge
 * @param outputPx the square edge to encode, in pixels
 *
 * Returns null when the bytes aren't a decodable image, so a caller can fall back to uploading
 * the original rather than losing the picture.
 */
expect fun cropSquare(
    source: ByteArray,
    originX: Float,
    originY: Float,
    size: Float,
    outputPx: Int = 512,
): ByteArray?
