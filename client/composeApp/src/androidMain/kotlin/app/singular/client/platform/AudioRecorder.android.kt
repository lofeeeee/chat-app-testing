package app.singular.client.platform

import android.media.MediaPlayer
import android.media.MediaRecorder
import app.singular.client.SingularApp
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Android capture: `MediaRecorder` writing AAC in an `.m4a` container.
 *
 * Hardware-encoded, which is the whole argument for using it over `AudioRecord` + a software
 * codec: AAC-LC is what the platform does natively, so a 30-second voice note costs tens of
 * kilobytes instead of the ~2.9 MB raw PCM would. `AudioRecord` only wins when you need the
 * samples live — for level metering we read `maxAmplitude` instead, which is enough for a
 * meter and costs nothing.
 *
 * ## Peaks
 *
 * `MediaRecorder` gives no samples, so peaks come from `maxAmplitude` sampled while recording:
 * one bucket per tick, which is a *coarser* waveform than desktop's true per-bucket peak but
 * still reads correctly to a person — the shape is what the eye sees, not the exact amplitude.
 *
 * ## Threading
 *
 * Recording is fast and non-blocking; the file is written to the app's cache dir and read into
 * memory on stop so the upload path is byte-for-byte the same as desktop's.
 */
actual class AudioRecorder {

    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var startedAt = 0L
    private var ticker: Thread? = null

    @Volatile
    private var currentLevel = 0f

    @Volatile
    private var lastElapsed = 0L

    @Volatile
    private var finished: RecordedAudio? = null

    private val levels = mutableListOf<Int>()

    actual val level: Float get() = currentLevel
    actual val elapsedMs: Long
        get() = if (startedAt > 0) System.currentTimeMillis() - startedAt else lastElapsed

    actual fun start(onError: (String) -> Unit) {
        try {
            val file = File.createTempFile("voice", ".m4a", cacheDir()).also { output = it }
            val rec = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(SAMPLE_RATE)
                setAudioEncodingBitRate(BIT_RATE)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = rec
            levels.clear()
            startedAt = System.currentTimeMillis()

            ticker = thread(name = "voice-level", isDaemon = true) {
                while (recorder != null) {
                    // maxAmplitude is 0..32767 on most devices; normalise to 0..1.
                    currentLevel = (rec.maxAmplitude / 20000f).coerceIn(0f, 1f)
                    levels += (currentLevel * 100).toInt().coerceIn(0, 100)
                    Thread.sleep(LEVEL_TICK_MS)
                }
            }
        } catch (e: Exception) {
            release()
            onError(e.message ?: "Couldn't open the microphone")
        }
    }

    actual fun stop(): RecordedAudio? {
        val rec = recorder ?: return null
        val file = output
        val duration = System.currentTimeMillis() - startedAt
        try {
            rec.stop()
        } catch (e: Exception) {
            // stop() throws if nothing was recorded; a zero-length take is a cancel, not a bug.
            release()
            return null
        }
        release()

        val bytes = file?.takeIf { it.exists() }?.readBytes()
        lastElapsed = duration
        if (bytes == null || bytes.isEmpty()) return null

        return RecordedAudio(
            bytes = bytes,
            mimeType = "audio/mp4",
            durationMs = duration.toInt(),
            peaks = downsample(levels),
        ).also { finished = it }
    }

    actual fun cancel() {
        runCatching { recorder?.stop() }
        release()
        output?.delete()
        finished = null
    }

    private fun release() {
        ticker = null
        runCatching { recorder?.release() }
        recorder = null
        currentLevel = 0f
        startedAt = 0L
    }

    private fun downsample(values: List<Int>): List<Int> {
        if (values.isEmpty()) return emptyList()
        if (values.size <= MAX_PEAKS) return values
        val step = values.size / MAX_PEAKS
        return (0 until MAX_PEAKS).map { i ->
            // Max within the window, not the average: averaging flattens the transients that
            // make a waveform look like speech.
            var peak = 0
            for (j in (i * step) until ((i + 1) * step).coerceAtMost(values.size)) {
                val v = values[j]
                if (abs(v) > peak) peak = abs(v)
            }
            peak
        }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        /** 64 kbps mono is plenty for speech and is what voice-note clients land on. */
        const val BIT_RATE = 64_000
        const val LEVEL_TICK_MS = 50L
        const val MAX_PEAKS = 256
    }
}

/** Android playback: `MediaPlayer` over the bytes we already hold, via a temp file. */
actual class AudioPlayer {

    private var player: MediaPlayer? = null
    private var watcher: Thread? = null
    private var temp: File? = null

    @Volatile
    actual var isPlaying: Boolean = false
        private set

    @Volatile
    actual var positionSeconds: Float = 0f
        private set

    actual fun play(bytes: ByteArray, mimeType: String, onEnded: () -> Unit) {
        stop()
        val file = runCatching {
            File.createTempFile("play", ".m4a", cacheDir()).apply { writeBytes(bytes) }
        }.getOrNull() ?: run {
            onEnded()
            return
        }
        temp = file

        val mp = runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { isPlaying = false; positionSeconds = 0f; onEnded() }
                prepare()
                start()
            }
        }.getOrNull()
        if (mp == null) {
            onEnded()
            return
        }
        player = mp
        isPlaying = true

        watcher = thread(name = "voice-position", isDaemon = true) {
            while (isPlaying) {
                positionSeconds = runCatching { mp.currentPosition / 1000f }.getOrDefault(0f)
                Thread.sleep(100)
            }
        }
    }

    actual fun stop() {
        isPlaying = false
        runCatching { player?.release() }
        player = null
        watcher = null
        temp?.delete()
        temp = null
        positionSeconds = 0f
    }
}

/**
 * The app's cache directory. Recording needs a filesystem path — `MediaRecorder` won't take a
 * stream — so both actuals share this one place that knows where scratch files go.
 */
private fun cacheDir(): File? = SingularApp.appContext.cacheDir
