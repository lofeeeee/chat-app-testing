package app.singular.client.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.singular.client.net.AttachmentDto
import app.singular.client.net.LocationDto
import kotlin.math.roundToInt

/**
 * Renders whatever a message carries besides text.
 *
 * Shape is chosen by `kind`, which the **server** assigned after actually decoding the bytes â€”
 * not by the filename or the declared MIME type. A file that claims to be an image but isn't
 * gets reclassified server-side, so this never hands a malformed payload to an image decoder.
 */
@Composable
fun AttachmentBlock(
    attachments: List<AttachmentDto>,
    location: LocationDto?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty() && location == null) return

    Column(modifier.widthIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { attachment ->
            when {
                attachment.isVoice -> VoiceNote(attachment, tint)
                attachment.isImage -> ImageAttachment(attachment, tint)
                else -> FileAttachment(attachment, tint)
            }
        }
        location?.let { LocationCard(it, tint) }
    }
}

/**
 * An image, in a box sized from the server-recorded dimensions.
 *
 * The frame is laid out **before** any pixels arrive, using the width and height the server
 * measured at finalize time. That is what stops the message list jumping around as images
 * load â€” the single most irritating thing a chat client can do while you're reading it.
 *
 * The thumbnail is preferred over the original: it is exactly what the server generated for
 * this purpose, and a 4 MB photo has no business crossing the wire to fill a 300dp box.
 */
@Composable
private fun ImageAttachment(attachment: AttachmentDto, tint: Color) {
    val width = attachment.width ?: 0
    val height = attachment.height ?: 0
    val ratio = if (width > 0 && height > 0) height.toFloat() / width else 0.62f
    val boxWidth = 300.dp
    val source = attachment.thumbnailUrl ?: attachment.url

    Box(
        Modifier
            .width(boxWidth)
            .height(boxWidth * ratio.coerceIn(0.3f, 1.6f))
            .clip(MaterialTheme.shapes.small)
            .background(tint.copy(alpha = 0.10f))
            .border(1.dp, tint.copy(alpha = 0.18f), MaterialTheme.shapes.small),
        contentAlignment = Alignment.Center,
    ) {
        if (source != null) {
            RemoteImage(
                url = source,
                // The attachment id, never the URL: presigned URLs get a fresh signature on
                // every fetch, so keying the cache on one would miss every single time.
                stableKey = attachment.id,
                contentDescription = attachment.filename,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Still uploading, or no signature could be minted. The frame stays put so the
            // layout is identical once it resolves.
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = attachment.filename,
                    tint = tint.copy(alpha = 0.5f),
                    modifier = Modifier.size(34.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    attachment.readableSize,
                    style = MaterialTheme.typography.labelSmall,
                    color = tint.copy(alpha = 0.65f),
                )
            }
        }
    }
}

@Composable
private fun FileAttachment(attachment: AttachmentDto, tint: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(tint.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Description, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                attachment.filename,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = tint,
                maxLines = 1,
            )
            Text(
                attachment.readableSize,
                style = MaterialTheme.typography.labelSmall,
                color = tint.copy(alpha = 0.7f),
            )
        }
    }
}

/**
 * A voice note drawn from precomputed peaks.
 *
 * The waveform comes down with the message as 0â€“100 integers, so the bar renders instantly
 * without downloading and decoding the audio â€” which is the difference between a list that
 * scrolls and one that stalls on every voice message.
 */
@Composable
private fun VoiceNote(attachment: AttachmentDto, tint: Color) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(tint.copy(alpha = 0.10f))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))

        Canvas(Modifier.weight(1f).height(26.dp)) {
            // A flat line when there are no peaks: an empty gap would read as a broken message,
            // and some encoders simply don't give us peaks.
            val peaks = attachment.waveform.ifEmpty { List(28) { 22 } }
            val barWidth = size.width / (peaks.size * 1.7f)
            val gap = barWidth * 0.7f
            val centre = size.height / 2f

            peaks.forEachIndexed { index, peak ->
                val amplitude = (peak.coerceIn(0, 100) / 100f) * centre
                val x = index * (barWidth + gap)
                drawLine(
                    color = tint.copy(alpha = 0.85f),
                    start = Offset(x, centre - amplitude.coerceAtLeast(1.5f)),
                    end = Offset(x, centre + amplitude.coerceAtLeast(1.5f)),
                    strokeWidth = barWidth,
                )
            }
        }

        Spacer(Modifier.width(10.dp))
        Text(
            formatDuration(attachment.durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = tint.copy(alpha = 0.75f),
        )
    }
}

@Composable
private fun LocationCard(location: LocationDto, tint: Color) {
    val live = location.expiresAt != null

    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(tint.copy(alpha = 0.10f))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.LocationOn, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                location.label ?: if (live) "Live location" else "Shared a location",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = tint,
            )
            // Five decimal places is roughly a metre â€” enough to be useful, and it stops the
            // card showing sixteen digits of float noise.
            Text(
                "${trim5(location.latitude)}, ${trim5(location.longitude)}",
                style = MaterialTheme.typography.labelSmall,
                color = tint.copy(alpha = 0.7f),
            )
        }
    }
}

private fun trim5(value: Double): String {
    val scaled = (value * 100_000).roundToInt() / 100_000.0
    return scaled.toString()
}

private fun formatDuration(ms: Int?): String {
    if (ms == null || ms <= 0) return "0:00"
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:" + seconds.toString().padStart(2, '0')
}
