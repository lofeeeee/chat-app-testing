package app.singular.client.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
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
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
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
    val colors = LocalSingularColors.current

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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "Sign in with phone",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = colors.text,
            )
            Text(
                "Scan this code with a device where you're already signed in.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.textMuted,
                textAlign = TextAlign.Center,
            )
        }

        // Viewfinder surface framing the QR code
        Surface(
            shape = SingularShapes.medium,
            color = colors.surface,
            modifier = Modifier.border(1.dp, colors.line, SingularShapes.medium),
        ) {
            Box(Modifier.padding(20.dp), contentAlignment = Alignment.Center) {
                // Subtle camera viewfinder corner brackets
                val bracketColor = colors.accent
                Canvas(Modifier.size(244.dp)) {
                    val stroke = 2.5f.dp.toPx()
                    val len = 18.dp.toPx()
                    // Top-left
                    drawLine(bracketColor, Offset(0f, 0f), Offset(len, 0f), stroke)
                    drawLine(bracketColor, Offset(0f, 0f), Offset(0f, len), stroke)
                    // Top-right
                    drawLine(bracketColor, Offset(size.width, 0f), Offset(size.width - len, 0f), stroke)
                    drawLine(bracketColor, Offset(size.width, 0f), Offset(size.width, len), stroke)
                    // Bottom-left
                    drawLine(bracketColor, Offset(0f, size.height), Offset(len, size.height), stroke)
                    drawLine(bracketColor, Offset(0f, size.height), Offset(0f, size.height - len), stroke)
                    // Bottom-right
                    drawLine(bracketColor, Offset(size.width, size.height), Offset(size.width - len, size.height), stroke)
                    drawLine(bracketColor, Offset(size.width, size.height), Offset(size.width, size.height - len), stroke)
                }

                when (qr.phase) {
                    QrPhase.WAITING, QrPhase.SCANNED -> {
                        val payload = qr.qrPayload
                        if (payload == null) {
                            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp)
                            }
                        } else {
                            QrCode(payload, size = 220.dp)
                        }
                    }

                    QrPhase.APPROVED -> Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text("Approved", style = MaterialTheme.typography.titleMedium, color = colors.accent)
                            qr.approvedBy?.let {
                                Text(
                                    "by $it",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted,
                                )
                            }
                        }
                    }

                    else -> Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                qr.message ?: "Code unavailable",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textMuted,
                                textAlign = TextAlign.Center,
                            )
                            Button(
                                onClick = { qr.start() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = colors.accent,
                                    contentColor = colors.onAccent,
                                ),
                                shape = SingularShapes.small,
                                modifier = Modifier.pressScale(),
                            ) {
                                Text("New code")
                            }
                        }
                    }
                }
            }
        }

        when (qr.phase) {
            QrPhase.WAITING -> {
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
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(SingularShapes.extraSmall),
                        color = colors.accent,
                        trackColor = colors.sunken,
                    )
                    Text(
                        "Rotates in ${qr.secondsUntilRotate}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textFaint,
                    )
                }
            }

            QrPhase.SCANNED -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = colors.accent)
                Spacer(Modifier.width(10.dp))
                Text(
                    "Scanned — confirm on your other device",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.accentSoft,
                )
            }

            else -> Spacer(Modifier.height(1.dp))
        }

        if (qr.phase == QrPhase.WAITING || qr.phase == QrPhase.SCANNED) {
            TextButton(
                onClick = { qr.start() },
                modifier = Modifier.pressScale(),
            ) {
                Text("Get a new code", color = colors.textMuted)
            }
        }
    }
}
