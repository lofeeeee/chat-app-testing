package app.singular.client.platform

/**
 * Voice notes: capture and playback, per platform.
 *
 * The recording half is the one genuinely platform-specific piece of feature 6 — there is no
 * cross-platform capture API, and the two platforms don't even agree on the container: desktop
 * gives raw PCM and Android gives an encoded file. So both actuals return the same shape
 * (encoded bytes + duration + peaks) and the rest of the app never knows which it got.
 *
 * Peaks are computed **at record time**, not uploaded from a decode pass: the waveform is a
 * property of the audio as it was captured, and the server stores whatever we send (see
 * `finalizeUpload`), so the client is the only place that can produce them honestly.
 */
data class RecordedAudio(
    val bytes: ByteArray,
    val mimeType: String,
    val durationMs: Int,
    /** Amplitudes 0..100, downsampled to at most 256 buckets. Drives the waveform without
     *  downloading and decoding the audio on every render. */
    val peaks: List<Int>,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is RecordedAudio && mimeType == other.mimeType &&
            durationMs == other.durationMs && peaks == other.peaks && bytes.contentEquals(other.bytes))

    override fun hashCode(): Int =
        31 * (31 * (31 * mimeType.hashCode() + durationMs) + peaks.hashCode()) + bytes.contentHashCode()
}

/**
 * Starts recording. Reports failure through [onError] rather than throwing, so a denied
 * microphone permission reads as a message in the composer instead of an unhandled exception.
 *
 * `stop()` returns null when nothing was captured — an empty take is a cancel, not an error.
 */
expect class AudioRecorder() {
    /** Live capture level, 0..1, for the recording indicator. */
    val level: Float
    val elapsedMs: Long

    fun start(onError: (String) -> Unit)
    fun stop(): RecordedAudio?
    fun cancel()
}

/**
 * Plays one clip at a time. A single shared player, because two voice notes playing over each
 * other is never what anyone meant — starting one stops the last.
 */
expect class AudioPlayer() {
    val isPlaying: Boolean
    /** Seconds elapsed, updated by the platform's own progress source. */
    val positionSeconds: Float

    fun play(bytes: ByteArray, mimeType: String, onEnded: () -> Unit)
    fun stop()
}
