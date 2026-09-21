package app.singular.client.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.singular.client.platform.readLocalList
import app.singular.client.platform.writeLocalList

/**
 * The emoji picker: a compact panel with a search field, category tabs, and a scrollable
 * grid, plus a "recently used" row fed from local storage.
 *
 * Two entry points share it: the composer button and the reaction sheet. They differ only in
 * what happens to a pick, so the panel takes an [onPick] and knows nothing about either.
 */

private const val RECENTS_KEY = "emoji_recents"
private const val RECENTS_MAX = 24

/**
 * Emoji cell size. 44dp, not the 34 it used to be: Material's minimum touch target is 48dp,
 * and the picker is the most-tapped surface after the composer — at 34dp with 2dp spacing it
 * mis-tapped constantly on touch. 44 is the compromise between that and grid density.
 */
private val EMOJI_CELL = 44.dp

/**
 * Remembers and persists the recently-used list.
 *
 * Storage is synchronous and tiny; writes happen on the pick path, which is a user action
 * with natural throttling (nobody taps 24 emoji a second).
 */
@Composable
fun rememberRecentEmoji(): androidx.compose.runtime.MutableState<List<String>> {
    val state = remember { mutableStateOf(readLocalList(RECENTS_KEY)) }
    return state
}

fun noteEmojiUsed(recents: androidx.compose.runtime.MutableState<List<String>>, emoji: String) {
    val next = (listOf(emoji) + recents.value.filter { it != emoji }).take(RECENTS_MAX)
    recents.value = next
    writeLocalList(RECENTS_KEY, next)
}

/**
 * One Shift-key tracker for the whole picker, not one per cell.
 *
 * The old shape ran a `while(true) awaitPointerEvent` coroutine inside every `EmojiCell` —
 * dozens of coroutines alive while the picker was open, all to answer one question. The
 * modifier state is now observed once at the root (where any pointer in the panel passes
 * through) and read by the cells through this local.
 */
val LocalShiftHeld = androidx.compose.runtime.compositionLocalOf { false }

@Composable
fun EmojiPicker(
    recents: androidx.compose.runtime.MutableState<List<String>>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(EmojiCategory.SMILEYS) }

    // Observed on the Initial pass so the value is current before any cell's click handler
    // runs — the same ordering guarantee the per-cell version had, minus the per-cell cost.
    val shiftTracker = remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val shiftModifier = Modifier.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                shiftTracker.value = event.keyboardModifiers.isShiftPressed
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(LocalShiftHeld provides shiftTracker.value) {
    Surface(
        modifier = modifier.then(shiftModifier),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(8.dp)) {
            // -- Search ----------------------------------------------------------

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                placeholder = { Text("Search emoji") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
            )

            Spacer(Modifier.height(8.dp))

            // The recents row and the category tabs collapse when a query is active, but via
            // AnimatedVisibility rather than an `if` — an instant vanish made the panel's
            // height snap twice per keystroke, which reads as the layout fighting the user.
            val sectionTransition = expandVertically() + fadeIn() to shrinkVertically() + fadeOut()

            androidx.compose.animation.AnimatedVisibility(
                visible = query.isBlank() && recents.value.isNotEmpty(),
                enter = sectionTransition.first,
                exit = sectionTransition.second,
            ) {
                Column {
                    Text(
                        "Recently used",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(8),
                        modifier = Modifier.height(40.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        userScrollEnabled = false,
                    ) {
                        items(recents.value.take(8)) { emoji ->
                            EmojiCell(emoji, cellSize = 38.dp) { keepOpen ->
                                noteEmojiUsed(recents, emoji)
                                onPick(emoji)
                                if (!keepOpen) onClose()
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // -- Category tabs ---------------------------------------------------

            androidx.compose.animation.AnimatedVisibility(
                visible = query.isBlank(),
                enter = sectionTransition.first,
                exit = sectionTransition.second,
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        EmojiCategory.entries
                            .filter { it != EmojiCategory.RECENT }
                            .forEach { cat ->
                                val selected = cat == category
                                Surface(
                                    onClick = { category = cat },
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                ) {
                                    Text(
                                        cat.label,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    )
                                }
                            }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }

            // -- Grid ------------------------------------------------------------

            val entries = remember(query, category) {
                if (query.isBlank()) emojiFor(category) else searchEmoji(query)
            }

            if (entries.isEmpty()) {
                Text(
                    if (query.isBlank()) "Nothing here yet." else "No emoji match “$query”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 20.dp),
                )
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(EMOJI_CELL),
                    modifier = Modifier.height(216.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(entries, key = { it.name }) { entry ->
                        EmojiCell(entry.emoji) { keepOpen ->
                            noteEmojiUsed(recents, entry.emoji)
                            onPick(entry.emoji)
                            // One emoji is the common case, so a plain click finishes the job
                            // and gets out of the way. Shift keeps the panel up for a run of
                            // them — the same "hold Shift to keep going" people already know
                            // from multi-select everywhere else.
                            if (!keepOpen) onClose()
                        }
                    }
                }
            }
        }
    }
    }
}

/**
 * One tappable emoji, drawn with the bundled font.
 *
 * Hover/press feedback: a soft background wash plus a slight scale on hover. Without it the
 * grid gave no signal under the pointer at all, which reads as dead UI rather than calm UI.
 * The scale is a graphicsLayer — no recomposition, no re-layout, draw-only.
 */
@Composable
private fun EmojiCell(emoji: String, cellSize: Dp = EMOJI_CELL, onClick: (keepOpen: Boolean) -> Unit) {
    val font = emojiFontFamily()

    // Shift state comes from the picker root's tracker via [LocalShiftHeld] — see the
    // comment there for why this is not per-cell.
    val shiftHeld = LocalShiftHeld.current

    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        targetValue = if (hovered) 1.12f else 1f,
        animationSpec = spring(stiffness = 400f),
        label = "emoji-hover",
    )

    Box(
        Modifier
            .size(cellSize)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (hovered) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(interactionSource = interaction, indication = null) { onClick(shiftHeld) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            emoji,
            fontFamily = font,
            fontSize = 22.sp,
        )
    }
}
