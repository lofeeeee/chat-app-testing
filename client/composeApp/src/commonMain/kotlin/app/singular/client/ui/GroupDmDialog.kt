package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.singular.client.AppState
import app.singular.client.net.UserDto

/**
 * Feature 2's missing half: create a group DM.
 *
 * The server has carried `type = GROUP_DM` and an owner since the baseline migration; the
 * schema mutation and this picker are what was missing. Everyone in the list joins
 * immediately — an owner-only invite flow can come later, and starting without it matches how
 * group chats actually form: somebody decides, everyone wakes up in it.
 *
 * The member source is the DM-derived contact list — everyone you already have a 1:1
 * conversation with. That is this app's whole "friends" model (see FriendsTab), so the picker
 * shows exactly the people you can already talk to. Add-by-handle lives one tab over on the
 * Friends screen, which covers the ones you can't pick here yet.
 */
@Composable
fun GroupDmDialog(
    state: AppState,
    onDismiss: () -> Unit,
    onCreate: (userIds: List<String>, name: String?) -> Unit,
) {
    // Copy-on-open: the picker owns its own selection, so closing without creating leaves
    // nothing behind for the next open to accidentally inherit.
    val contacts = remember { state.dmContacts() }
    val selected = remember { mutableStateListOf<String>() }
    var name by remember { mutableStateOf("") }

    val focus = LocalFocusManager.current
    val nameField = remember { FocusRequester() }

    val confirm = {
        if (selected.isNotEmpty()) onCreate(selected.toList(), name.trim().ifEmpty { null })
    }

    fun toggle(user: UserDto) {
        if (user.id in selected) selected.remove(user.id) else selected.add(user.id)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New group conversation") },
        text = {
            DialogKeys(onDismiss = onDismiss, onConfirm = confirm, confirmEnabled = selected.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Everyone you pick joins immediately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(64) },
                        label = { Text("Name (optional)") },
                        placeholder = { Text("Weekend plans") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(nameField)
                            .formField(focus, selected.isNotEmpty(), confirm),
                    )

                    if (contacts.isEmpty()) {
                        Text(
                            "No contacts yet — open a direct message with someone first, and " +
                                "they'll be pickable here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${selected.size} of ${contacts.size} chosen",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f),
                            )
                            if (selected.isNotEmpty()) {
                                TextButton(onClick = { selected.clear() }) { Text("Clear") }
                            }
                        }

                        LazyColumn(
                            Modifier
                                .fillMaxWidth()
                                // Bounded so a long contact list scrolls inside the dialog
                                // rather than growing it past the window.
                                .heightIn(max = 280.dp)
                        ) {
                            items(contacts, key = { it.id }) { person ->
                                ContactRow(
                                    person = person,
                                    status = state.statusOf(person),
                                    checked = person.id in selected,
                                    onToggle = { toggle(person) },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = confirm, enabled = selected.isNotEmpty()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One pickable person: avatar, name, handle, and a checkbox whose target is the whole row. */
@Composable
private fun ContactRow(
    person: UserDto,
    status: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(
                if (checked) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            )
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AvatarWithStatus(person, status, 30)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                person.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                person.handle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Checkbox(checked = checked, onCheckedChange = null)
    }
}

/**
 * The contact list for the group picker, shared with FriendsTab's derivation. One definition
 * of "who can I put in a group" — the two screens can't drift apart if there's only one.
 */
internal fun AppState.dmContacts(): List<UserDto> {
    val selfId = currentUser?.id ?: return emptyList()
    return channels
        .filter { it.type == "DM" }
        .mapNotNull { channel -> channel.members.firstOrNull { it.id != selfId } }
        .distinctBy { it.id }
}
