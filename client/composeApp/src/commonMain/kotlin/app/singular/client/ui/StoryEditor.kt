package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.singular.client.AppState
import app.singular.client.platform.PickedFile

/**
 * The story editor.
 *
 * A full screen rather than the dialog this replaces. The old composer collected a caption and
 * opened a file picker, which is fine for one line of text and hopeless the moment there is a
 * background, a font, a size, an alignment and several text elements to arrange — a dialog
 * cannot show a live preview *and* the controls that change it, and without the preview every
 * choice is made blind.
 *
 * Two kinds of story, chosen first because the rest of the screen depends on it:
 *
 *  * **Text** — words on an authored background. No image at all.
 *  * **Photo** — an image, with a caption pinned along the bottom *and* any number of free
 *    text elements placed around it. The caption and the free text are separate on purpose:
 *    the caption is the story's one line of prose and the free text is decoration, so adding
 *    a sticker-ish flourish must not overwrite what you actually wanted to say.
 *
 * Everything produced here is **overlay JSON plus a background id**, never a flattened image.
 * That is what keeps a story restylable and lets a mention re-render with someone's current
 * name — see [StoryOverlayCanvas].
 */
@Composable
fun StoryEditor(state: AppState, onClose: () -> Unit) {
    var mode by remember { mutableStateOf(StoryMode.TEXT) }
    var background by remember { mutableStateOf(StoryBackgrounds.default) }
    var caption by remember { mutableStateOf("") }
    var image by remember { mutableStateOf<PickedFile?>(null) }

    // The free text elements, newest last. A state list so edits to an element redraw the
    // preview without rebuilding the whole editor.
    val elements = remember { mutableStateListOf<StoryOverlay>() }
    var selected by remember { mutableStateOf(0) }

    // A text story with nothing written on it is not a story. A photo story is complete the
    // moment there is a photo, caption or not.
    val postable = when (mode) {
        StoryMode.TEXT -> elements.any { !it.value.isNullOrBlank() }
        StoryMode.PHOTO -> image != null
    }

    fun overlays(): List<StoryOverlay> = buildList {
        addAll(elements.filter { !it.value.isNullOrBlank() })
        if (mode == StoryMode.PHOTO && caption.isNotBlank()) {
            add(StoryOverlay(type = "caption", x = 0.5f, y = 0.86f, value = caption.trim()))
        }
    }

    // Text mode opens with one empty element, so there is something to type into rather than a
    // blank canvas and an "Add text" button to find first.
    remember(mode) {
        if (mode == StoryMode.TEXT && elements.isEmpty()) {
            elements.add(newTextElement(y = 0.44f))
            selected = 0
        }
        true
    }

    Column(Modifier.fillMaxSize()) {
        EditorHeader(
            busy = state.uploadProgress != null,
            postable = postable,
            onClose = onClose,
            onPost = {
                state.postStory(
                    overlaysJson = encodeOverlays(overlays()),
                    backgroundId = if (mode == StoryMode.TEXT) background.id else null,
                    image = image,
                )
                onClose()
            },
        )

        state.uploadProgress?.let {
            LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
        }
        HorizontalDivider()

        Row(Modifier.fillMaxSize()) {
            // The preview gets the room and the inspector gets a fixed column. The other way
            // round — a preview squeezed into whatever is left — is what makes editors feel
            // like forms.
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                StoryPreview(
                    mode = mode,
                    background = background,
                    image = image,
                    overlays = overlays(),
                )
            }

            HorizontalDivider(Modifier.width(1.dp).fillMaxHeight())

            Column(
                Modifier
                    .width(320.dp)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ModePicker(mode) { picked ->
                    mode = picked
                    if (picked == StoryMode.TEXT) image = null
                }

                when (mode) {
                    StoryMode.TEXT -> BackgroundPicker(background) { background = it }

                    StoryMode.PHOTO -> {
                        PhotoSection(
                            image = image,
                            busy = state.uploadProgress != null,
                            onPick = { state.pickStoryImage { image = it } },
                        )
                        OutlinedTextField(
                            value = caption,
                            onValueChange = { caption = it.take(160) },
                            label = { Text("Caption") },
                            supportingText = { Text("Sits along the bottom, like WhatsApp.") },
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        HorizontalDivider()
                    }
                }

                TextElementList(
                    elements = elements,
                    selected = selected,
                    onSelect = { selected = it },
                    onAdd = {
                        // Stacked down the frame so a second element doesn't land exactly on
                        // the first and look like nothing happened.
                        elements.add(newTextElement(y = 0.2f + 0.14f * (elements.size % 4)))
                        selected = elements.lastIndex
                    },
                    onRemove = { index ->
                        elements.removeAt(index)
                        selected = selected.coerceAtMost(elements.lastIndex).coerceAtLeast(0)
                    },
                )

                elements.getOrNull(selected)?.let { element ->
                    HorizontalDivider()
                    TextInspector(element) { updated -> elements[selected] = updated }
                }
            }
        }
    }
}

enum class StoryMode { TEXT, PHOTO }

/** A fresh element: white, centred, mid-size. The settings most people never change. */
private fun newTextElement(y: Float) = StoryOverlay(
    type = "text",
    x = 0.5f,
    y = y,
    value = "",
    color = "#FFFFFF",
    font = "sans",
    size = 26f,
    align = "center",
)

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun EditorHeader(
    busy: Boolean,
    postable: Boolean,
    onClose: () -> Unit,
    onPost: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 20.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text("New story", style = MaterialTheme.typography.titleLarge)
            Text(
                "Disappears after 24 hours.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Button(onClick = onPost, enabled = postable && !busy) {
            Text(if (busy) "Posting…" else "Post")
        }
    }
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

/**
 * The live preview, at a story's real 9:16.
 *
 * Constrained to the frame ratio rather than filling the pane, because the overlay coordinates
 * are fractions of the frame — previewing them in a differently shaped box would put the text
 * somewhere it will not be when anyone actually watches it, which defeats the point of having
 * a preview.
 */
@Composable
private fun StoryPreview(
    mode: StoryMode,
    background: StoryBackground,
    image: PickedFile?,
    overlays: List<StoryOverlay>,
) {
    Box(
        Modifier
            .fillMaxHeight()
            .aspectRatio(9f / 16f)
            .clip(RoundedCornerShape(14.dp))
            .background(
                if (mode == StoryMode.TEXT) background.brush
                else androidx.compose.ui.graphics.SolidColor(Color(0xFF101010))
            ),
    ) {
        if (mode == StoryMode.PHOTO) {
            if (image != null) {
                LocalImage(
                    bytes = image.bytes,
                    contentDescription = "Chosen photo",
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Image,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.4f),
                            modifier = Modifier.size(40.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Choose a photo",
                            color = Color.White.copy(alpha = 0.6f),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }

        StoryOverlayCanvas(overlays = overlays, modifier = Modifier.fillMaxSize())
    }
}

// ---------------------------------------------------------------------------
// Inspector sections
// ---------------------------------------------------------------------------

@Composable
private fun ModePicker(mode: StoryMode, onPick: (StoryMode) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InspectorLabel("Story type")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = mode == StoryMode.TEXT,
                onClick = { onPick(StoryMode.TEXT) },
                label = { Text("Text") },
                leadingIcon = {
                    Icon(Icons.Filled.TextFields, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
            FilterChip(
                selected = mode == StoryMode.PHOTO,
                onClick = { onPick(StoryMode.PHOTO) },
                label = { Text("Photo") },
                leadingIcon = {
                    Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
        }
    }
}

@Composable
private fun BackgroundPicker(selected: StoryBackground, onPick: (StoryBackground) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        InspectorLabel("Background")
        // A wrapping grid of swatches drawn with the real brush. A list of names would make
        // you click each one to find out what it looks like.
        StoryBackgrounds.all.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { option ->
                    Box(
                        Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(option.brush)
                            .border(
                                width = if (option.id == selected.id) 2.5.dp else 1.dp,
                                color = if (option.id == selected.id) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable { onPick(option) },
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Text(
                            option.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PhotoSection(image: PickedFile?, busy: Boolean, onPick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InspectorLabel("Photo")
        OutlinedButton(onClick = onPick, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (image == null) "Choose a photo" else "Replace photo")
        }
        image?.let {
            Text(
                "${it.name} · ${it.sizeBytes / 1024} KB",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TextElementList(
    elements: List<StoryOverlay>,
    selected: Int,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            InspectorLabel("Text", Modifier.weight(1f))
            TextButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }

        elements.forEachIndexed { index, element ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (index == selected) MaterialTheme.colorScheme.surfaceVariant
                        else Color.Transparent
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    element.value?.takeIf { it.isNotBlank() } ?: "Empty",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (element.value.isNullOrBlank())
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(26.dp)) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove this text",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Everything about the selected run of text: words, font, size, alignment, colour, position. */
@Composable
private fun TextInspector(element: StoryOverlay, onChange: (StoryOverlay) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        OutlinedTextField(
            value = element.value.orEmpty(),
            onValueChange = { onChange(element.copy(value = it.take(200))) },
            label = { Text("Words") },
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InspectorLabel("Font")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StoryFonts.forEach { (id, label) ->
                    FilterChip(
                        selected = (element.font ?: "sans") == id,
                        onClick = { onChange(element.copy(font = id)) },
                        // Each chip is set in the font it selects — the only honest way to
                        // show what you are choosing.
                        label = { Text(label, fontFamily = storyFontFamily(id)) },
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            InspectorLabel("Size — ${(element.size ?: 26f).toInt()}")
            Slider(
                value = element.size ?: 26f,
                onValueChange = { onChange(element.copy(size = it)) },
                valueRange = 12f..64f,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InspectorLabel("Alignment")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AlignButton(Icons.AutoMirrored.Filled.FormatAlignLeft, "start", element, onChange)
                AlignButton(Icons.Filled.FormatAlignCenter, "center", element, onChange)
                AlignButton(Icons.AutoMirrored.Filled.FormatAlignRight, "end", element, onChange)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InspectorLabel("Colour")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StoryInk.forEach { hex ->
                    val swatch = Color(0xFF000000L.toInt() or hex.removePrefix("#").toLong(16).toInt())
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(swatch)
                            .border(
                                width = if (element.color == hex) 3.dp else 1.dp,
                                color = if (element.color == hex) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.outline,
                                shape = CircleShape,
                            )
                            .clickable { onChange(element.copy(color = hex)) }
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InspectorLabel("Style")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = element.style != "plate",
                    onClick = { onChange(element.copy(style = "plain")) },
                    label = { Text("Plain") },
                )
                FilterChip(
                    selected = element.style == "plate",
                    onClick = { onChange(element.copy(style = "plate")) },
                    label = { Text("Highlighted") },
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            // Vertical only. Horizontal placement is what the alignment control does, and
            // offering both would let you set them to disagree.
            InspectorLabel("Position — ${(element.y * 100).toInt()}% down")
            Slider(
                value = element.y,
                onValueChange = { onChange(element.copy(y = it)) },
                valueRange = 0.02f..0.9f,
            )
        }
    }
}

@Composable
private fun AlignButton(
    icon: ImageVector,
    value: String,
    element: StoryOverlay,
    onChange: (StoryOverlay) -> Unit,
) {
    val active = (element.align ?: "center") == value
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable { onChange(element.copy(align = value)) },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = value,
            modifier = Modifier.size(18.dp),
            tint = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                   else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InspectorLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}
