package app.singular.client.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.singular.client.QrLoginState
import app.singular.client.QrPhase

/**
 * The QR sign-in panel.
 *
 * The countdown bar is not decoration. A code that silently swaps every 20 seconds looks broken
 * to someone lining up their camera; showing the remaining time makes the rotation read as
 * deliberate, and tells them when to expect a fresh one.
 */
@Composable
fun QrLoginPanel(
    qr: QrLoginState,
    rotateSeconds: Int = 20,
    modifier: Modifier = Modifier,
) {
    // Tie the lifetime of the rotation loop and the socket to the panel being on screen.
    // Leaving them running behind a hidden tab burns a socket and keeps minting codes nobody
    // can see.
    DisposableEffect(Unit) {
        qr.start()
        onDispose { qr.cancel() }
    }

    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Sign in with your phone", style = MaterialTheme.typography.titleMedium)
        Text(
            "Open Singular on a device you're already signed in on, then scan this code.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
        ) {
            Box(Modifier.padding(16.dp), contentAlignment = Alignment.Center) {
                when (qr.phase) {
                    QrPhase.WAITING, QrPhase.SCANNED -> {
                        val payload = qr.qrPayload
                        if (payload == null) {
                            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator()
                            }
                        } else {
                            QrCode(payload, size = 240.dp)
                        }
                    }

                    QrPhase.APPROVED -> Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Approved", style = MaterialTheme.typography.titleMedium)
                            qr.approvedBy?.let {
                                Text(
                                    "by $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    else -> Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                qr.message ?: "Code unavailable",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { qr.start() }) { Text("New code") }
                        }
                    }
                }
            }
        }

        when (qr.phase) {
            QrPhase.WAITING -> {
                // Drains left to right over the rotation interval, then snaps back on the swap.
                val progress by animateFloatAsState(
                    targetValue = qr.secondsUntilRotate.toFloat() / rotateSeconds,
                    animationSpec = tween(durationMillis = 900),
                    label = "qr-rotation",
                )
                Column(
                    Modifier.width(240.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "New code in ${qr.secondsUntilRotate}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            QrPhase.SCANNED -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Scanned â€” confirm on your other device",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            else -> Spacer(Modifier.height(1.dp))
        }

        if (qr.phase == QrPhase.WAITING || qr.phase == QrPhase.SCANNED) {
            TextButton(onClick = { qr.start() }) { Text("Get a new code") }
        }
    }
}
