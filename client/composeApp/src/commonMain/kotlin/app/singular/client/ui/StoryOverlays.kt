package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
    /** `sans`, `serif`, `mono` or `cursive`. Resolved by [storyFontFamily]. */
    val font: String? = null,
    /** Type size in sp. Absolute, not a multiplier â€” see [StoryText]. */
    val size: Float? = null,
    /** `start`, `center` or `end`. */
    val align: String? = null,
    // Music widget (feature 20)
    val title: String? = null,
    val artist: String? = null,
)

/**
 * Parses the overlay list the server round-trips as opaque JSON.
 *
 * The server deliberately never interprets this â€” it stores and returns it â€” so the client owns
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
                font = obj["font"]?.jsonPrimitive?.content,
                size = obj["size"]?.jsonPrimitive?.content?.toFloatOrNull(),
                align = obj["align"]?.jsonPrimitive?.content,
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
            o.font?.let { append(",\"font\":\"").append(escape(it)).append("\"") }
            o.size?.let { append(",\"size\":").append(it) }
            o.align?.let { append(",\"align\":\"").append(escape(it)).append("\"") }
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
 * restyled, re-localised or corrected without re-uploading a byte â€” and it is why mentions
 * inside a story re-render with someone's *current* name rather than freezing the one they had
 * when it was posted.
 *
 * [BoxWithConstraints] supplies the frame size that turns fractional coordinates into pixels.
 */
@Composable
fun StoryOverlayCanvas(
    overlays: List<StoryOverlay>,
    modifier: Modifier = Modifier,
    editable: Boolean = false,
    selectedIndex: Int = -1,
    onSelect: (Int) -> Unit = {},
    onChange: (Int, StoryOverlay) -> Unit = { _, _ -> },
) {
    BoxWithConstraints(modifier) {
        val frameWidth = maxWidth
        val frameHeight = maxHeight

        // Gesture deltas arrive in pixels; overlay coordinates are fractions of the frame. The
        // conversion needs a density, and this is the one scope that has both the constraints
        // and a composable context â€” so it happens once here, rather than being re-derived (or
        // worse, guessed from Dp.value) inside every gesture.
        val density = LocalDensity.current
        val frameWidthPx = with(density) { frameWidth.toPx() }.coerceAtLeast(1f)
        val frameHeightPx = with(density) { frameHeight.toPx() }.coerceAtLeast(1f)

        // Two positioning models, because text and objects want different things.
        //
        // **Text flows in bands.** A text element occupies a full-width strip at its `y` and
        // aligns itself inside that strip. This is how story text actually behaves, and it
        // sidesteps a real problem with point-placement: an offset puts an element's *corner*
        // at (x, y), so centring a run of text would need its measured width, which isn't
        // known until after layout. Bands make "centred" exact at any frame size and any
        // string length.
        //
        // **Objects sit at points.** A sticker someone dragged somewhere has a position, not
        // an alignment, so those keep the fractional offset.
        overlays.forEachIndexed { index, overlay ->
            val selected = editable && index == selectedIndex

            when (overlay.type) {
                "caption" -> CaptionOverlay(overlay)

                "text" -> TextBand(
                    overlay = overlay,
                    frameHeight = frameHeight,
                    editable = editable,
                    selected = selected,
                    onTap = { if (editable) onSelect(index) },
                    // Text drags vertically only. Its `x` is an alignment, not a coordinate â€”
                    // letting a drag write both would put the two controls in a position the
                    // inspector can no longer describe.
                    onDragY = { delta ->
                        if (editable) {
                            onChange(
                                index,
                                overlay.copy(
                                    y = (overlay.y + delta / frameHeightPx).coerceIn(0.02f, 0.92f),
                                ),
                            )
                        }
                    },
                    onTransform = { zoom, rotation ->
                        if (editable) {
                            onChange(
                                index,
                                overlay.copy(
                                    scale = (overlay.scale * zoom).coerceIn(0.3f, 4f),
                                    rotation = overlay.rotation + rotation,
                                ),
                            )
                        }
                    },
                )

                else -> ObjectAtPoint(
                    overlay = overlay,
                    frameWidth = frameWidth,
                    frameHeight = frameHeight,
                    editable = editable,
                    selected = selected,
                    onTap = { if (editable) onSelect(index) },
                    onDrag = { dx, dy ->
                        if (editable) {
                            onChange(
                                index,
                                overlay.copy(
                                    x = (overlay.x + dx / frameWidthPx).coerceIn(0f, 1f),
                                    y = (overlay.y + dy / frameHeightPx).coerceIn(0f, 1f),
                                ),
                            )
                        }
                    },
                    onTransform = { zoom, rotation ->
                        if (editable) {
                            onChange(
                                index,
                                overlay.copy(
                                    scale = (overlay.scale * zoom).coerceIn(0.3f, 4f),
                                    rotation = overlay.rotation + rotation,
                                ),
                            )
                        }
                    },
                ) {
                    when (overlay.type) {
                        "sticker", "emoji" -> StickerOverlay(overlay)
                        "music" -> MusicOverlay(overlay)
                        "mention" -> MentionOverlay(overlay)
                        "location" -> LocationOverlay(overlay)
                        // Unknown type from a newer client: skipped silently. Drawing a
                        // placeholder box would be worse than the sticker simply not
                        // appearing.
                        else -> Unit
                    }
                }
            }
        }
    }
}

/**
 * A text element: the band, plus the drag/rotate/zoom handles when editing.
 *
 * Transform gestures are combined into one `detectTransformGestures` rather than separate
 * drag and scale detectors, because people pinch *while* dragging â€” two independent detectors
 * would make the element jump between the two each time a second finger landed.
 */
@Composable
private fun TextBand(
    overlay: StoryOverlay,
    frameHeight: androidx.compose.ui.unit.Dp,
    editable: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onDragY: (deltaPx: Float) -> Unit,
    onTransform: (zoom: Float, rotation: Float) -> Unit,
) {
    Box(
        Modifier
            .offset(y = frameHeight * overlay.y.coerceIn(0f, 1f))
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .then(if (editable) Modifier.gestureLayer(onTap, onDragY, onTransform) else Modifier)
            .rotate(overlay.rotation)
            .scale(overlay.scale.coerceIn(0.3f, 4f)),
        contentAlignment = when (overlay.align) {
            "start" -> Alignment.CenterStart
            "end" -> Alignment.CenterEnd
            else -> Alignment.Center
        },
    ) {
        TextOverlay(overlay)
        if (selected) SelectionRing()
    }
}

/** A sticker, mention, location pin or music widget: placed at a point, fully transformable. */
@Composable
private fun ObjectAtPoint(
    overlay: StoryOverlay,
    frameWidth: androidx.compose.ui.unit.Dp,
    frameHeight: androidx.compose.ui.unit.Dp,
    editable: Boolean,
    selected: Boolean,
    onTap: () -> Unit,
    onDrag: (dxPx: Float, dyPx: Float) -> Unit,
    onTransform: (zoom: Float, rotation: Float) -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier
            .offset(
                x = frameWidth * overlay.x.coerceIn(0f, 1f),
                y = frameHeight * overlay.y.coerceIn(0f, 1f),
            )
            .then(
                if (editable) {
                    Modifier.gestureLayer(
                        onTap = onTap,
                        onDrag = onDrag,
                        onTransform = onTransform,
                    )
                } else Modifier
            )
            // Rotate before scaling so a tilted sticker grows along its own axis rather than
            // shearing.
            .rotate(overlay.rotation)
            .scale(overlay.scale.coerceIn(0.3f, 4f)),
    ) {
        content()
        if (selected) SelectionRing()
    }
}

/**
 * Tap to select, drag to move, pinch to scale, twist to rotate â€” on one element.
 *
 * [onDragY] vs [onDrag]: text uses the one-axis variant because its horizontal position is an
 * alignment, so both are offered and callers pick the one that matches their model.
 */
private fun Modifier.gestureLayer(
    onTap: () -> Unit,
    onDrag: ((dxPx: Float, dyPx: Float) -> Unit)? = null,
    onDragY: ((deltaPx: Float) -> Unit)? = null,
    onTransform: (zoom: Float, rotation: Float) -> Unit,
): Modifier = this
    .pointerInput(Unit) {
        detectTapGestures(onTap = { onTap() })
    }
    .pointerInput(Unit) {
        detectTransformGestures(
            // Pan is handled here, not in a separate drag detector: a two-finger pinch that
            // also moves must not fight a drag for the same pointer stream.
            panZoomLock = false,
        ) { centroid, pan, zoom, rotation ->
            if (zoom != 1f || rotation != 0f) onTransform(zoom, rotation)
            if (pan != androidx.compose.ui.geometry.Offset.Zero) {
                onDrag?.invoke(pan.x, pan.y) ?: onDragY?.invoke(pan.y)
            }
        }
    }

/** The dashed box that says which element the inspector is editing. */
@Composable
private fun BoxScope.SelectionRing() {
    Box(
        Modifier
            .matchParentSize()
            .padding(4.dp)
            .border(
                width = 1.5.dp,
                color = Color.White.copy(alpha = 0.9f),
                shape = RoundedCornerShape(6.dp),
            )
    )
}

}

/**
 * The font families a story can use.
 *
 * Only the four families every platform already has. Shipping a font file would be the obvious
 * alternative and it is the wrong one here: the project vendors everything offline, and a
 * bundled face costs megabytes in the jar for a choice most people make once. Unknown names
 * fall back to sans rather than throwing â€” same forward-compatibility contract as everything
 * else that crosses the wire as a string.
 */
fun storyFontFamily(name: String?): FontFamily = when (name) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

/** The four names, in picker order, with labels. */
val StoryFonts: List<Pair<String, String>> = listOf(
    "sans" to "Sans",
    "serif" to "Serif",
    "mono" to "Mono",
    "cursive" to "Script",
)

/**
 * The caption, WhatsApp-style: centred, low in the frame, on a barely-there dark scrim.
 *
 * Deliberately the one overlay type that ignores `x`/`y`. A caption is not a sticker someone
 * placed â€” it is the frame's own furniture, and it has to sit in the same spot on a portrait
 * phone photo and a wide desktop screenshot. Positioning it fractionally like the others is
 * what pushed it off-centre and into the middle of the picture at different aspect ratios.
 *
 * The scrim is near-transparent rather than a solid plate: enough to hold white text over a
 * bright photo, not enough to hide what is behind it â€” which is the whole balance a caption
 * has to strike. It also spans the full width so the band reads as a deliberate edge rather
 * than a box floating in the middle of the image.
 */
@Composable
private fun BoxScope.CaptionOverlay(overlay: StoryOverlay) {
    val text = overlay.value.orEmpty()
    if (text.isBlank()) return

    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .fillMaxWidth()
            // Clear of the very bottom edge, where a viewer's own controls sit.
            .padding(bottom = 28.dp)
            .background(Color.Black.copy(alpha = 0.28f))
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = parseHexColor(overlay.color) ?: Color.White,
            fontFamily = storyFontFamily(overlay.font),
            fontSize = (overlay.size ?: 17f).coerceIn(10f, 40f).sp,
            fontWeight = FontWeight.Medium,
            textAlign = when (overlay.align) {
                "start" -> TextAlign.Start
                "end" -> TextAlign.End
                else -> TextAlign.Center
            },
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TextOverlay(overlay: StoryOverlay) {
    val text = overlay.value.orEmpty()
    if (text.isBlank()) return

    val tint = parseHexColor(overlay.color) ?: Color.White
    val alignment = when (overlay.align) {
        "start" -> TextAlign.Start
        "end" -> TextAlign.End
        else -> TextAlign.Center
    }

    when (overlay.style) {
        // A filled plate behind the words, so light text stays readable over a bright photo.
        // The plate takes the chosen colour and the text flips to whichever of black or white
        // survives on it â€” picking the fill and the ink separately is how you get white on
        // yellow.
        "plate" -> StoryText(
            overlay = overlay,
            text = text,
            color = if (tint.luminanceIsLight()) Color.Black else Color.White,
            align = alignment,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(tint)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )

        // A shadow rather than a plate: enough to survive a busy photo without boxing the
        // words in. Plain text over an unknown image is otherwise a coin toss.
        else -> StoryText(
            overlay = overlay,
            text = text,
            color = tint,
            align = alignment,
            shadow = true,
        )
    }
}

/**
 * One run of story text, with the author's font, size and alignment applied.
 *
 * Size is stored in **sp and absolute**, not as a multiplier of a base. A multiplier reads
 * fine until two stories composed on differently sized screens are viewed side by side and the
 * "same" size renders differently; an absolute value is the same everywhere, which is the
 * whole promise of compositing overlays at view time instead of baking them in.
 */
@Composable
private fun StoryText(
    overlay: StoryOverlay,
    text: String,
    color: Color,
    align: TextAlign,
    modifier: Modifier = Modifier,
    shadow: Boolean = false,
) {
    Text(
        text,
        color = color,
        fontFamily = storyFontFamily(overlay.font),
        fontSize = (overlay.size ?: 22f).coerceIn(10f, 72f).sp,
        fontWeight = FontWeight.Bold,
        textAlign = align,
        style = if (!shadow) LocalTextStyle.current else LocalTextStyle.current.copy(
            shadow = Shadow(
                color = Color.Black.copy(alpha = 0.55f),
                offset = Offset(0f, 2f),
                blurRadius = 8f,
            )
        ),
        modifier = modifier,
    )
}

@Composable
private fun StickerOverlay(overlay: StoryOverlay) {
    // The bundled emoji font, not the platform's: stickers are the one place a story is
    // *mostly* emoji, and per-platform glyphs here would change the look of a story between
    // the phone that posted it and the desktop reading it.
    Text(
        overlay.value.orEmpty(),
        fontSize = (overlay.size?.toInt() ?: 44).sp,
        fontFamily = emojiFontFamily(),
    )
}

/**
 * The music widget (feature 20).
 *
 * Deliberately metadata only â€” title, artist, and a link out. Streaming a clip of a commercial
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
