package app.singular.client.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.singular.client.platform.AudioRecorder
import app.singular.client.platform.RecordedAudio
import kotlinx.coroutines.delay

/**
 * The recording bar, shown in place of the composer while a voice note is being captured.
 *
 * Three controls and no waveform editor: record, discard, send. Voice notes are the one kind of
 * message people expect to fire off or throw away, and an editing surface between those two
 * choices would only get in the way.
 *
 * ## Levels
 *
 * The meter is driven by the recorder's RMS, read on a 50 ms tick rather than pushed by the
 * platform. Polling a getter is the fit for a capture API that exposes level but no callback —
 * and at 20 Hz it's indistinguishable from one.
 */
@Composable
fun RecordingBar(
    recorder: AudioRecorder,
    onCancel: () -> Unit,
    onSend: (RecordedAudio) -> Unit,
    onError: (String) -> Unit,
) {
    var elapsed by remember { mutableStateOf(0L) }
    var level by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        recorder.start(onError)
        while (true) {
            elapsed = recorder.elapsedMs
            level = recorder.level
            delay(50)
        }
    }

    DisposableEffect(Unit) {
        onDispose { recorder.cancel() }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Discard. Text rather than an icon: "cancel" and "delete" read very differently to
        // someone who has just recorded something, and only one of them means "throw it away".
        IconButton(onClick = onCancel) {
            Icon(Icons.Filled.Delete, contentDescription = "Discard recording")
        }

        Spacer(Modifier.width(8.dp))

        // Live level: a dot that swells, because a waveform of a take in progress implies a
        // precision the meter doesn't have.
        val dotSize by animateFloatAsState(
            targetValue = 10f + level * 14f,
            animationSpec = tween(80),
            label = "record-level",
        )
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(dotSize.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.7f + level * 0.3f))
            )
        }

        Spacer(Modifier.width(10.dp))

        Row(
            Modifier.weight(1f),
            horizontalArrangement = Arrangement.Start,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Mic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                formatVoiceDuration(elapsed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }

        FilledIconButton(
            onClick = {
                val audio = recorder.stop()
                if (audio != null) onSend(audio) else onError("Nothing was recorded.")
            },
        ) {
            Icon(Icons.Filled.Send, contentDescription = "Send voice note")
        }
    }
}

/** "0:07" — voice notes are short, so minutes are the only unit worth showing. */
fun formatVoiceDuration(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
