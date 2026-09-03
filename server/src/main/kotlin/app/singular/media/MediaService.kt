package app.singular.media

import app.singular.config.SingularProperties
import app.singular.core.Forbidden
import app.singular.core.InvalidInput
import app.singular.core.NotFound
import app.singular.core.Snowflake
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.math.roundToInt

enum class AttachmentKind(val code: Short) {
    FILE(0), IMAGE(1), VIDEO(2), AUDIO(3), VOICE_NOTE(4);

    companion object {
        fun ofCode(code: Short) = entries.firstOrNull { it.code == code } ?: FILE

        /** Inferred from the declared MIME type, then re-checked against the stored bytes. */
        fun forContentType(contentType: String, voiceNote: Boolean): AttachmentKind = when {
            voiceNote -> VOICE_NOTE
            contentType.startsWith("image/") -> IMAGE
            contentType.startsWith("video/") -> VIDEO
            contentType.startsWith("audio/") -> AUDIO
            else -> FILE
        }
    }
}

enum class AttachmentStatus(val code: Short) {
    PENDING(0), READY(1), FAILED(2);

    companion object {
        fun ofCode(code: Short) = entries.firstOrNull { it.code == code } ?: PENDING
    }
}

data class Attachment(
    val id: Long,
    val uploaderId: Long,
    val messageId: Long?,
    val objectKey: String,
    val filename: String,
    val contentType: String,
    val sizeBytes: Long,
    val kind: AttachmentKind,
    val status: AttachmentStatus,
    val width: Int?,
    val height: Int?,
    val thumbnailKey: String?,
    val durationMs: Int?,
    val waveform: List<Int>,
)

data class UploadSlot(val attachment: Attachment, val uploadUrl: String)

@Service
class MediaService(
    private val attachments: AttachmentRepository,
    private val storage: StorageService,
    private val snowflake: Snowflake,
    private val props: SingularProperties,
) {

    /**
     * Step 1: hand out an upload slot.
     *
     * The row is created PENDING and the URL is signed with the declared content type and
     * length, so the client cannot substitute a different file. Nothing about this row is
     * trusted until [finalizeUpload] has checked the object itself.
     */
    @Transactional
    fun createUpload(
        uploaderId: Long,
        filename: String,
        contentType: String,
        sizeBytes: Long,
        voiceNote: Boolean = false,
    ): UploadSlot {
        if (sizeBytes <= 0) throw InvalidInput("That file is empty.")
        if (sizeBytes > props.media.maxUploadBytes) {
            throw InvalidInput("Files are limited to ${props.media.maxUploadBytes / 1024 / 1024} MB.")
        }

        val safeName = sanitizeFilename(filename)
        val type = normalizeContentType(contentType)
        val kind = AttachmentKind.forContentType(type, voiceNote)

        if (kind == AttachmentKind.IMAGE && sizeBytes > props.media.maxImageBytes) {
            throw InvalidInput("Images are limited to ${props.media.maxImageBytes / 1024 / 1024} MB.")
        }

        val id = snowflake.next()
        // Sharded by uploader and id so no single storage prefix accumulates every object —
        // and so a listing of one user's uploads never has to scan the whole bucket.
        val key = "att/$uploaderId/$id/${safeName}"

        attachments.insert(id, uploaderId, key, safeName, type, sizeBytes, kind)

        return UploadSlot(
            attachment = attachments.find(id) ?: error("Attachment $id vanished after insert"),
            uploadUrl = storage.presignUpload(key, type, sizeBytes),
        )
    }

    /**
     * Step 3: verify what was actually stored, then make it usable.
     *
     * This is the step that makes direct-to-storage upload safe. Until it runs, the row is a
     * claim; after it, the server has HEADed the object, confirmed the size matches what was
     * declared, stripped EXIF from images and produced a thumbnail.
     */
    @Transactional
    fun finalizeUpload(attachmentId: Long, uploaderId: Long, durationMs: Int?, waveform: List<Int>?): Attachment {
        val attachment = attachments.find(attachmentId) ?: throw NotFound("Upload")
        if (attachment.uploaderId != uploaderId) throw Forbidden("that upload")
        if (attachment.status == AttachmentStatus.READY) return attachment

        val stored = storage.head(attachment.objectKey)
            ?: throw InvalidInput("The upload didn't arrive. Try again.")

        // The signed URL pins content-length, so a mismatch means something is wrong with the
        // storage layer rather than the client — but failing loudly beats trusting it.
        if (stored.sizeBytes != attachment.sizeBytes) {
            attachments.markFailed(attachmentId)
            throw InvalidInput("Uploaded size didn't match what was declared.")
        }

        var width: Int? = null
        var height: Int? = null
        var thumbnailKey: String? = null

        if (attachment.kind == AttachmentKind.IMAGE) {
            val processed = processImage(attachment)
            width = processed?.width
            height = processed?.height
            thumbnailKey = processed?.thumbnailKey
        }

        attachments.markReady(
            id = attachmentId,
            width = width,
            height = height,
            thumbnailKey = thumbnailKey,
            // Duration and waveform come from the client: decoding audio server-side needs a
            // full media stack, and neither value is security-relevant — the worst a lie
            // produces is a wrong-looking waveform.
            durationMs = durationMs?.coerceIn(0, 24 * 60 * 60 * 1000),
            waveform = waveform?.take(256)?.map { it.coerceIn(0, 100) },
        )
        return attachments.find(attachmentId) ?: error("Attachment vanished after finalize")
    }

    private data class ProcessedImage(val width: Int, val height: Int, val thumbnailKey: String?)

    /**
     * Strips metadata and builds a thumbnail.
     *
     * **EXIF stripping is not cosmetic.** Phone cameras embed GPS coordinates in JPEGs by
     * default, so an unprocessed holiday photo posted to a public channel publishes the
     * sender's location, and a photo taken at home publishes their address. Re-encoding
     * through ImageIO drops every metadata block, which is the blunt but reliable way to do it.
     *
     * It has to happen server-side. Stripping in the client would be faster, but a client can
     * simply not do it, and the one that doesn't is the one leaking.
     *
     * This runs inline today. It belongs on a queue once uploads get busy — it is the only
     * part of the pipeline that holds a request thread while touching real bytes.
     */
    private fun processImage(attachment: Attachment): ProcessedImage? {
        val bytes = storage.download(attachment.objectKey) ?: return null

        val image = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        if (image == null) {
            // Declared image/*, but not decodable. Left as an opaque file rather than trusted:
            // serving it as an image is how a malformed payload reaches an image decoder.
            LOG.warn("Attachment {} claimed {} but did not decode", attachment.id, attachment.contentType)
            attachments.reclassify(attachment.id, AttachmentKind.FILE)
            return null
        }

        val stripped = ByteArrayOutputStream().use { out ->
            // Re-encoding to PNG for lossless formats and JPEG otherwise keeps the pixels and
            // discards every EXIF, XMP and IPTC block along with them.
            val format = if (attachment.contentType == "image/png") "png" else "jpg"
            val flattened = if (format == "jpg") flatten(image) else image
            ImageIO.write(flattened, format, out)
            out.toByteArray()
        }
        storage.upload(attachment.objectKey, stripped, attachment.contentType)

        val thumbnailKey = runCatching {
            val thumb = scaleToFit(image, props.media.thumbnailMaxEdge)
            val key = "${attachment.objectKey}.thumb.jpg"
            ByteArrayOutputStream().use { out ->
                ImageIO.write(flatten(thumb), "jpg", out)
                storage.upload(key, out.toByteArray(), "image/jpeg")
            }
            key
        }.getOrNull()

        return ProcessedImage(image.width, image.height, thumbnailKey)
    }

    /** JPEG has no alpha channel; writing an ARGB image to it produces a black or pink mess. */
    private fun flatten(source: BufferedImage): BufferedImage {
        if (source.type == BufferedImage.TYPE_INT_RGB) return source
        val flat = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
        val g = flat.createGraphics()
        g.color = java.awt.Color.WHITE
        g.fillRect(0, 0, source.width, source.height)
        g.drawImage(source, 0, 0, null)
        g.dispose()
        return flat
    }

    private fun scaleToFit(source: BufferedImage, maxEdge: Int): BufferedImage {
        val scale = maxEdge.toDouble() / maxOf(source.width, source.height)
        if (scale >= 1.0) return source

        val w = (source.width * scale).roundToInt().coerceAtLeast(1)
        val h = (source.height * scale).roundToInt().coerceAtLeast(1)
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = out.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        g.drawImage(source, 0, 0, w, h, null)
        g.dispose()
        return out
    }

    fun downloadUrl(key: String): String = storage.presignDownload(key)

    @Transactional
    fun attachToMessage(attachmentIds: List<Long>, messageId: Long, channelId: Long, uploaderId: Long) {
        attachmentIds.forEach { id ->
            val attachment = attachments.find(id) ?: throw NotFound("Attachment")
            if (attachment.uploaderId != uploaderId) throw Forbidden("that attachment")
            if (attachment.status != AttachmentStatus.READY) {
                throw InvalidInput("That upload hasn't finished yet.")
            }
            // Guarded so an attachment can only ever be claimed by one message: without it,
            // resending the same id would silently move someone's file between conversations.
            if (!attachments.claim(id, messageId, channelId)) {
                throw InvalidInput("That file has already been sent.")
            }
        }
    }

    /**
     * Filenames become part of an object key and are echoed back to every viewer.
     *
     * Path separators and `..` are stripped so a name can't escape its prefix, and the result
     * is capped — some systems will happily accept a 4 KB filename and then fail elsewhere.
     */
    private fun sanitizeFilename(raw: String): String {
        val base = raw.substringAfterLast('/').substringAfterLast('\\')
            .replace("..", "")
            .filter { it.isLetterOrDigit() || it in "-_. ()[]" }
            .trim()
            .take(120)
        return base.ifEmpty { "file" }
    }

    private fun normalizeContentType(raw: String): String {
        val cleaned = raw.substringBefore(';').trim().lowercase()
        // An empty or absurd type would end up signed into the URL and served back verbatim.
        if (cleaned.isEmpty() || cleaned.length > 100 || !cleaned.contains('/')) {
            return "application/octet-stream"
        }
        return cleaned
    }

    private companion object {
        val LOG = LoggerFactory.getLogger(MediaService::class.java)!!
    }
}
