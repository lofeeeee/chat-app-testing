package app.singular.client.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.singular.client.AppState
import app.singular.client.net.GuildDto

/**
 * Server settings.
 *
 * Everything here changes the server for everybody in it, which is why it is a deliberate
 * dialog rather than inline controls in the sidebar: renaming a server should take a decision,
 * not a mis-click on a header.
 *
 * The identity fields (name, description) are staged locally and saved on one button, so a
 * half-typed name never reaches the other members. The invite section is the opposite — codes
 * are minted immediately, because a code you have to "save" to obtain would be a strange
 * thing indeed.
 */
@Composable
fun ServerSettingsDialog(state: AppState, guild: GuildDto, onClose: () -> Unit) {
    var name by remember(guild.id) { mutableStateOf(guild.name) }
    var description by remember(guild.id) { mutableStateOf(guild.description.orEmpty()) }
    var nickname by remember(guild.id) { mutableStateOf(guild.me?.nickname.orEmpty()) }

    val owner = state.currentUser?.id == guild.ownerId
    val dirty = name.trim() != guild.name || description.trim() != guild.description.orEmpty()

    LaunchedEffect(guild.id) { state.loadInvites(guild.id) }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Server settings") },
        text = {
          // Escape closes; Enter is not bound, because this dialog has several independent
          // save buttons and "the" confirm would be a guess about which one you meant.
          DialogKeys(onDismiss = onClose) {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // -- Identity -------------------------------------------------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ServerIcon(guild) { state.changeGuildIcon(guild.id) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Server icon", fontWeight = FontWeight.SemiBold)
                        Text(
                            if (owner) "Click the icon to upload a picture."
                            else "Only the owner can change this.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                state.uploadProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(100) },
                    label = { Text("Server name") },
                    singleLine = true,
                    enabled = owner,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(300) },
                    label = { Text("Description") },
                    placeholder = { Text("What this server is for") },
                    enabled = owner,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        state.updateGuild(guild.id, name = name, description = description)
                    },
                    enabled = owner && dirty && name.trim().length >= 2 && !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save changes") }

                HorizontalDivider()

                // -- Your identity in this server (feature 14) ----------------
                Text("Your nickname here", fontWeight = FontWeight.SemiBold)
                Text(
                    "How you appear in this server only. Leave it empty to use your display name.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = nickname,
                        onValueChange = { nickname = it.take(32) },
                        placeholder = { Text(guild.me?.user?.label.orEmpty()) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            state.setNickname(guild.id, nickname.trim().ifEmpty { null })
                        },
                        enabled = !state.busy,
                    ) { Text("Set") }
                }

                HorizontalDivider()

                // -- Invites --------------------------------------------------
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Invite links", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { state.createInvite(guild.id) }, enabled = !state.busy) {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("New code")
                    }
                }

                if (state.guildInvites.isEmpty()) {
                    Text(
                        "No invite codes yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Codes are shown in full, never truncated. A partially visible invite
                    // code is worthless — the entire purpose of the row is to be copied.
                    state.guildInvites.forEach { invite ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.small)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                invite.code,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                invite.maxUses?.let { "${invite.uses}/$it uses" }
                                    ?: "${invite.uses} uses",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                HorizontalDivider()

                // -- Channels -------------------------------------------------
                Text("Channels — ${guild.channels.count { it.type == "GUILD_TEXT" }}", fontWeight = FontWeight.SemiBold)
                Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                    guild.channels.filter { it.type == "GUILD_TEXT" }.forEach { channel ->
                        Text(
                            "#" + channel.name.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }

                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
          }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}

/**
 * The server's icon, or its initials until it has one.
 *
 * Initials are the fallback rather than a generic placeholder glyph, so a server without an
 * icon is still distinguishable from the other servers without one.
 */
@Composable
private fun ServerIcon(guild: GuildDto, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val url = guild.iconUrl
        if (url != null) {
            RemoteImage(
                url = url,
                // Keyed on iconKey, not the URL: the URL is presigned and different on every
                // fetch, so caching against it would miss every single time.
                stableKey = guild.iconKey ?: guild.id,
                contentDescription = "${guild.name} icon",
                modifier = Modifier.size(64.dp),
            )
        } else {
            Text(
                guild.initials,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        // Always on top of whichever of the two is showing: the affordance has to be visible
        // when there *is* an icon, which is exactly when you might want to replace it.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(22.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.PhotoCamera,
                contentDescription = "Change icon",
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}
