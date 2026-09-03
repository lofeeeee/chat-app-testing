package app.singular.client.platform

/** A file the user chose, already read into memory. */
data class PickedFile(
    val name: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    val sizeBytes: Long get() = bytes.size.toLong()

    // ByteArray uses identity equality, which would make two PickedFile values with the same
    // contents compare unequal and quietly break any `remember` keyed on one.
    override fun equals(other: Any?): Boolean =
        this === other || (other is PickedFile && name == other.name &&
            contentType == other.contentType && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int =
        31 * (31 * name.hashCode() + contentType.hashCode()) + bytes.contentHashCode()
}

/**
 * Opens the platform's file chooser.
 *
 * Returns null when the user cancels — cancelling is not an error and shouldn't surface one.
 *
 * Reads the whole file into memory, which is fine at the 100 MB upload ceiling and keeps the
 * upload path identical on every platform. Streaming would be the right call if that ceiling
 * ever rises, and this is the seam where it would change.
 */
expect suspend fun pickFile(imagesOnly: Boolean = false): PickedFile?

/**
 * Best-effort MIME type from a filename.
 *
 * Shared rather than per-platform because the server re-derives the kind from the bytes anyway;
 * this only needs to be close enough to sign the upload URL with.
 */
fun guessContentType(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "heic" -> "image/heic"
    "mp4" -> "video/mp4"
    "webm" -> "video/webm"
    "mov" -> "video/quicktime"
    "mp3" -> "audio/mpeg"
    "ogg", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "m4a" -> "audio/mp4"
    "pdf" -> "application/pdf"
    "txt", "md" -> "text/plain"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}
