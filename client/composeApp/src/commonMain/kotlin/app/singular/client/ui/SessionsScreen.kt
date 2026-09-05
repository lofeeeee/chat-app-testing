package app.singular.client.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.singular.client.SessionState
import app.singular.client.net.DeviceSessionDto

/**
 * "Where you're signed in", plus the approval side of QR sign-in.
 *
 * Each row is a rotation *family*, not a raw session — refresh rotation mints a new session row
 * every 15 minutes, and listing those would show one laptop dozens of times a day.
 */
@Composable
fun SessionsScreen(sessions: SessionState, onClose: () -> Unit) {
    var scanned by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current

    LaunchedEffect(Unit) { sessions.load() }

    sessions.pendingApproval?.let { request ->
        ApprovalDialog(
            platform = request.platform,
            ipAddress = request.ipAddress,
            userAgent = request.userAgent,
            onApprove = { sessions.approve() },
            onDeny = { sessions.deny() },
        )
    }

    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Spacer(Modifier.width(6.dp))
            Text("Devices", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text(
                "Esc to go back",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Approve a sign-in", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Paste the code from the other device's screen, or scan it with the camera.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = scanned,
                        onValueChange = { scanned = it },
                        label = { Text("Scanned code") },
                        singleLine = true,
                        modifier = Modifier.weight(1f).formField(
                            focus,
                            enabled = scanned.isNotBlank() && !sessions.busy,
                        ) { sessions.claim(scanned.trim()); scanned = "" },
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { sessions.claim(scanned.trim()); scanned = "" },
                        enabled = scanned.isNotBlank() && !sessions.busy,
                    ) { Text("Continue") }
                }
            }
        }

        sessions.message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider()

        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(sessions.devices, key = { it.id }) { device ->
                DeviceRow(device, onRevoke = { sessions.revoke(device.id) })
            }
        }

        if (sessions.devices.any { !it.current }) {
            TextButton(onClick = { sessions.revokeOthers() }) {
                Text("Sign out everywhere else")
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceSessionDto, onRevoke: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        device.label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (device.current) {
                        AssistChip(
                            onClick = {},
                            label = { Text("This device") },
                            colors = AssistChipDefaults.assistChipColors(
                                labelColor = MaterialTheme.colorScheme.primary,
                            ),
                        )
                    } else if (device.origin == "QR_CODE") {
                        // Worth calling out: a QR sign-in the user doesn't recognise is the
                        // shape a successful QRLJacking attack leaves behind.
                        AssistChip(onClick = {}, label = { Text("QR sign-in") })
                    }
                }
                Text(
                    listOfNotNull(device.ipAddress, device.userAgent?.take(48))
                        .joinToString(" · ")
                        .ifEmpty { "No connection details recorded" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Last active ${device.lastSeenAt.take(16).replace('T', ' ')}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!device.current) {
                TextButton(onClick = onRevoke) { Text("Sign out") }
            }
        }
    }
}

/**
 * The confirmation step, and the real security control in the whole QR flow.
 *
 * Rotating the code every 20 seconds bounds replay of a code captured off a screen. It does
 * nothing at all against someone showing you *their* login QR and asking you to scan it — that
 * attack ends with your account on their machine. The only defence is this screen naming the
 * device and address that is asking, so never auto-confirm it and never bury the details.
 */
@Composable
private fun ApprovalDialog(
    platform: String?,
    ipAddress: String?,
    userAgent: String?,
    onApprove: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text("Sign in on ${platform ?: "another device"}?") },
        text = {
            // Escape denies, and Enter is deliberately *not* bound to approve. Granting full
            // account access is the one decision in this app that has to be a deliberate
            // click — a stray Enter from the field behind this dialog must never approve a
            // sign-in someone else started.
            DialogKeys(onDismiss = onDeny) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "This will give that device full access to your account.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    DetailLine("Device", platform ?: "Unknown")
                    DetailLine("Address", ipAddress ?: "Unknown")
                    userAgent?.let { DetailLine("Client", it.take(64)) }
                    Text(
                        "If you didn't just start a sign-in on that device, deny this.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { Button(onClick = onApprove) { Text("Approve") } },
        dismissButton = { TextButton(onClick = onDeny) { Text("Deny") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(72.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
