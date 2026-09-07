package app.singular.client.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.singular.client.net.AttachmentDto
import app.singular.client.net.SingularClient
import app.singular.client.platform.AudioPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Plays voice notes — one at a time, for the whole app.
 *
 * A single shared player, held outside composition, because the alternative is a player per
 * message and the ability to have six voice notes playing at once. Starting a note stops
 * whichever was last, which is the behaviour everyone assumes anyway.
 *
 * ## Fetching
 *
 * The bytes are downloaded on the play tap, not when the message renders. Attachment URLs are
 * presigned and expire (see AttachmentDto), so hoarding audio for messages that may never be
 * played would both waste bandwidth and go stale.
 */
object VoiceNotePlayer {

    private val scope = CoroutineScope(Dispatchers.Default)
    private var player: AudioPlayer? = null
    private var loadJob: Job? = null

    /**
     * Set at sign-in. The client owns the connection and the session, so the player borrows it
     * rather than making its own — one HTTP stack, one set of credentials.
     */
    private var client: SingularClient? = null

    fun bind(httpClient: SingularClient) {
        client = httpClient
    }

    @Volatile
    private var currentId: String? = null

    /** Which attachment is playing, for the UI to reflect. */
    var activeId by mutableStateOf<String?>(null)
        private set

    fun isPlaying(attachmentId: String): Boolean = activeId == attachmentId && currentId == attachmentId

    fun play(
        attachment: AttachmentDto,
        onProgress: (seconds: Float, durationSeconds: Float) -> Unit,
        onStateChange: (Boolean) -> Unit,
    ) {
        val url = attachment.url ?: run {
            onStateChange(false)
            return
        }
        val http = client ?: run {
            onStateChange(false)
            return
        }

        stop()
        val id = attachment.id
        currentId = id
        activeId = id
        onStateChange(true)

        loadJob = scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { http.fetchBytes(url) }.getOrNull()
            }
            // Dropped or superseded while we were fetching: don't start a stale note.
            if (bytes == null || currentId != id) {
                if (currentId == id) {
                    activeId = null
                    currentId = null
                }
                onStateChange(false)
                return@launch
            }

            val duration = (attachment.durationMs ?: 0) / 1000f
            val fresh = AudioPlayer()
            player = fresh
            withContext(Dispatchers.Main) {
                fresh.play(bytes, mimeType(attachment)) {
                    if (currentId == id) {
                        activeId = null
                        currentId = null
                    }
                    onStateChange(false)
                }
            }
            // Progress is polled rather than pushed: 4 Hz is smooth enough for a waveform and
            // keeps the actuals from needing a timer callback in their interface.
            while (currentId == id && fresh.isPlaying) {
                onProgress(fresh.positionSeconds, duration)
                kotlinx.coroutines.delay(250)
            }
        }
    }

    fun stop() {
        loadJob?.cancel()
        loadJob = null
        runCatching { player?.stop() }
        player = null
        currentId = null
        activeId = null
    }

    private fun mimeType(attachment: AttachmentDto): String =
        attachment.contentType ?: "audio/mp4"
}
