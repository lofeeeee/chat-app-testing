package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One positioned element on a story.
 *
 * `x` and `y` are **fractions of the frame**, not pixels. A story composed on a phone and read
 * on a desktop has to put the sticker in the same place relative to the picture; absolute
 * coordinates would drift with every screen size.
 */
data class StoryOverlay(
    val type: String,
    val x: Float,
    val y: Float,
    val rotation: Float = 0f,
    val scale: Float = 1f,
    val value: String? = null,
    val style: String? = null,
    val color: String? = null,
    // Music widget (feature 20)
    val title: String? = null,
    val artist: String? = null,
)

/**
 * Parses the overlay list the server round-trips as opaque JSON.
 *
 * The server deliberately never interprets this — it stores and returns it — so the client owns
 * the shape and adding a new overlay type costs no migration and no server deploy.
 *
 * Anything malformed is skipped rather than failing the whole story: one bad sticker written by
 * a future client version should cost you that sticker, not the picture.
 */
fun parseOverlays(json: String): List<StoryOverlay> = runCatching {
    val root = Json.parseToJsonElement(json)
    if (root !is JsonArray) return emptyList()

    root.mapNotNull { element ->
        runCatching {
            val obj = element.jsonObject
            StoryOverlay(
                type = obj["type"]?.jsonPrimitive?.content ?: return@runCatching null,
                x = obj["x"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.5f,
                y = obj["y"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0.5f,
                rotation = obj["rot"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 0f,
                scale = obj["scale"]?.jsonPrimitive?.content?.toFloatOrNull() ?: 1f,
                value = obj["value"]?.jsonPrimitive?.content,
                style = obj["style"]?.jsonPrimitive?.content,
                color = obj["color"]?.jsonPrimitive?.content,
                title = obj["title"]?.jsonPrimitive?.content,
                artist = obj["artist"]?.jsonPrimitive?.content,
            )
        }.getOrNull()
    }
}.getOrDefault(emptyList())

/** Serialises overlays back out. Kept beside the parser so the two can't drift apart. */
fun encodeOverlays(overlays: List<StoryOverlay>): String =
    overlays.joinToString(",", "[", "]") { o ->
        buildString {
            append("{\"type\":\"").append(o.type).append("\"")
            append(",\"x\":").append(o.x)
            append(",\"y\":").append(o.y)
            append(",\"rot\":").append(o.rotation)
            append(",\"scale\":").append(o.scale)
            o.value?.let { append(",\"value\":\"").append(escape(it)).append("\"") }
            o.style?.let { append(",\"style\":\"").append(escape(it)).append("\"") }
            o.color?.let { append(",\"color\":\"").append(escape(it)).append("\"") }
            o.title?.let { append(",\"title\":\"").append(escape(it)).append("\"") }
            o.artist?.let { append(",\"artist\":\"").append(escape(it)).append("\"") }
            append("}")
        }
    }

private fun escape(raw: String): String =
    raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

/**
 * Draws overlays on top of a story's media.
 *
 * Composited at view time, never baked into the uploaded image. That is what lets a story be
 * restyled, re-localised or corrected without re-uploading a byte — and it is why mentions
 * inside a story re-render with someone's *current* name rather than freezing the one they had
 * when it was posted.
 *
 * [BoxWithConstraints] supplies the frame size that turns fractional coordinates into pixels.
 */
@Composable
fun StoryOverlayCanvas(overlays: List<StoryOverlay>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val frameWidth = maxWidth
        val frameHeight = maxHeight

        overlays.forEach { overlay ->
            Box(
                Modifier
                    .offset(
                        x = frameWidth * overlay.x.coerceIn(0f, 1f),
                        y = frameHeight * overlay.y.coerceIn(0f, 1f),
                    )
                    // Rotate before scaling so a tilted sticker grows along its own axis
                    // rather than shearing.
                    .rotate(overlay.rotation)
                    .scale(overlay.scale.coerceIn(0.3f, 4f)),
            ) {
                when (overlay.type) {
                    "text" -> TextOverlay(overlay)
                    "sticker", "emoji" -> StickerOverlay(overlay)
                    "music" -> MusicOverlay(overlay)
                    "mention" -> MentionOverlay(overlay)
                    "location" -> LocationOverlay(overlay)
                    // Unknown type from a newer client: skipped silently. Drawing a
                    // placeholder box would be worse than the sticker simply not appearing.
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun TextOverlay(overlay: StoryOverlay) {
    val text = overlay.value.orEmpty()
    val tint = parseHexColor(overlay.color) ?: Color.White

    when (overlay.style) {
        // A filled plate behind the words, so light text stays readable over a bright photo.
        "plate" -> Text(
            text,
            color = if (tint.luminanceIsLight()) Color.Black else Color.White,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .widthIn(max = 260.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(tint)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )

        else -> Text(
            text,
            color = tint,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 260.dp),
        )
    }
}

@Composable
private fun StickerOverlay(overlay: StoryOverlay) {
    Text(overlay.value.orEmpty(), fontSize = 44.sp)
}

/**
 * The music widget (feature 20).
 *
 * Deliberately metadata only — title, artist, and a link out. Streaming a clip of a commercial
 * track is what needs the label licensing that Instagram has and this app does not; showing
 * what someone is listening to and deep-linking into their own music app needs none of it.
 */
@Composable
private fun MusicOverlay(overlay: StoryOverlay) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MusicNote,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                overlay.title.orEmpty(),
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
            overlay.artist?.let {
                Text(
                    it,
                    color = Color.White.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun MentionOverlay(overlay: StoryOverlay) {
    Text(
        "@" + overlay.value.orEmpty(),
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun LocationOverlay(overlay: StoryOverlay) {
    Row(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.9f))
            .padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = Color(0xFFE1306C),
            modifier = Modifier.size(15.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            overlay.value.orEmpty(),
            color = Color.Black,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/** "#RRGGBB" or "RRGGBB". Anything else returns null and the caller picks a default. */
private fun parseHexColor(raw: String?): Color? {
    val hex = raw?.removePrefix("#")?.takeIf { it.length == 6 } ?: return null
    val value = hex.toLongOrNull(16) ?: return null
    return Color(0xFF000000L.toInt() or value.toInt())
}

/** Rough perceptual brightness, used to pick black or white text on a coloured plate. */
private fun Color.luminanceIsLight(): Boolean =
    (0.299f * red + 0.587f * green + 0.114f * blue) > 0.6f
