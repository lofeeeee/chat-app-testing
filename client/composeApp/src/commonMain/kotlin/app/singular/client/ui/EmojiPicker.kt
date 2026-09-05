package app.singular.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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

@Composable
fun EmojiPicker(
    recents: androidx.compose.runtime.MutableState<List<String>>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(EmojiCategory.SMILEYS) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(10.dp)) {
            // -- Search ----------------------------------------------------------

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().height(52.dp),
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

            if (query.isBlank() && recents.value.isNotEmpty()) {
                Text(
                    "Recently used",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(8),
                    modifier = Modifier.height(76.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    userScrollEnabled = false,
                ) {
                    items(recents.value.take(16)) { emoji ->
                        EmojiCell(emoji) {
                            noteEmojiUsed(recents, emoji)
                            onPick(emoji)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // -- Category tabs ---------------------------------------------------

            if (query.isBlank()) {
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
                    columns = GridCells.Adaptive(36.dp),
                    modifier = Modifier.height(236.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(entries, key = { it.name }) { entry ->
                        EmojiCell(entry.emoji) {
                            noteEmojiUsed(recents, entry.emoji)
                            onPick(entry.emoji)
                        }
                    }
                }
            }
        }
    }
}

/** One tappable emoji, drawn with the bundled font. */
@Composable
private fun EmojiCell(emoji: String, onClick: () -> Unit) {
    val font = emojiFontFamily()
    Box(
        Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            emoji,
            fontFamily = font,
            fontSize = 22.sp,
        )
    }
}
