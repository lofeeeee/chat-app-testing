package app.singular.client.platform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.TargetDataLine
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Desktop capture: `javax.sound.sampled` at 48 kHz 16-bit mono, written out as WAV.
 *
 * ## Why WAV here and Opus on Android
 *
 * Opus would be the right codec — a 30-second clip is ~2.9 MB as PCM and ~120 KB in Opus —
 * but the JVM has no Opus encoder in the JDK, and the pure-Java one (Concentus) is codec-only:
 * it produces raw Opus frames and leaves Ogg muxing, page CRCs and granule positions to the
 * caller. Hand-rolling a container is how you get audio that plays on the machine that wrote
 * it. So desktop ships uncompressed WAV, which every player opens, and Android ships
 * `MediaRecorder`'s AAC in an `.m4a` — hardware-encoded, small, and correct by construction.
 *
 * Both arrive at the app as the same [RecordedAudio], so the upload path is identical and the
 * desktop can move to Opus later by changing this one file.
 *
 * ## Threading
 *
 * Capture runs on a daemon thread and the mic is released in [stop]/[cancel]. A `TargetDataLine`
 * held open between takes keeps the OS recording indicator lit, which people read as the app
 * listening when it isn't.
 */
actual class AudioRecorder {

    private val format = AudioFormat(SAMPLE_RATE.toFloat(), 16, 1, true, false)
    private var line: TargetDataLine? = null
    private var worker: Thread? = null
    private val running = AtomicBoolean(false)
    private val startedAt = AtomicLong(0L)

    @Volatile
    private var currentLevel = 0f

    @Volatile
    private var captured = ByteArray(0)

    @Volatile
    private var elapsed = 0L

    actual val level: Float get() = currentLevel
    actual val elapsedMs: Long
        get() = if (running.get()) System.currentTimeMillis() - startedAt.get() else elapsed

    actual fun start(onError: (String) -> Unit) {
        try {
            val target = AudioSystem.getTargetDataLine(format).also {
                it.open(format)
                it.start()
            }
            line = target
            captured = ByteArray(0)
            startedAt.set(System.currentTimeMillis())
            running.set(true)

            val chunks = mutableListOf<ByteArray>()
            worker = thread(name = "voice-capture", isDaemon = true) {
                val buffer = ByteArray(BUFFER_BYTES)
                while (running.get()) {
                    val read = runCatching { target.read(buffer, 0, buffer.size) }.getOrElse { -1 }
                    if (read <= 0) break
                    chunks += buffer.copyOf(read)
                    // RMS, not peak: a single click shouldn't peg the meter, and RMS is what
                    // "how loud is this" means to someone watching it move.
                    var sum = 0.0
                    var i = 0
                    while (i + 1 < read) {
                        val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)).toShort().toInt()
                        sum += (sample / 32768.0) * (sample / 32768.0)
                        i += 2
                    }
                    currentLevel = (sqrt(sum / (read / 2.0).coerceAtLeast(1.0)) * SQRT_GAIN).toFloat().coerceIn(0f, 1f)
                }
                captured = concat(chunks)
            }
        } catch (e: Exception) {
            running.set(false)
            release()
            onError(e.message ?: "Couldn't open the microphone")
        }
    }

    actual fun stop(): RecordedAudio? {
        if (!running.get()) return null
        running.set(false)
        // Wait for the capture loop to flush its last chunk — but never indefinitely: a stuck
        // line must not hang the composer.
        runCatching { worker?.join(400) }
        val pcm = captured
        elapsed = System.currentTimeMillis() - startedAt.get()
        release()
        if (pcm.isEmpty()) return null

        return RecordedAudio(
            bytes = Wav.wrap(pcm, SAMPLE_RATE),
            mimeType = "audio/wav",
            durationMs = (pcm.size / 2 * 1000L / SAMPLE_RATE).toInt(),
            peaks = Peaks.fromPcm(pcm),
        )
    }

    actual fun cancel() {
        running.set(false)
        captured = ByteArray(0)
        release()
    }

    private fun release() {
        runCatching { line?.stop() }
        runCatching { line?.close() }
        line = null
        worker = null
        currentLevel = 0f
    }

    private fun concat(chunks: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream(chunks.sumOf { it.size })
        chunks.forEach(out::write)
        return out.toByteArray()
    }

    private companion object {
        /** 48 kHz: the rate every platform's voice pipeline ends up at, so nothing resamples. */
        const val SAMPLE_RATE = 48_000
        /** ~40 ms of 16-bit mono per read. */
        const val BUFFER_BYTES = 1920 * 2
        /** Speech at a normal level sits around 0.1–0.2 RMS; this maps that to a legible meter. */
        const val SQRT_GAIN = 3.5
    }
}

/**
 * Desktop playback: a `Clip`, not a `SourceDataLine`.
 *
 * One short voice note doesn't need a stream, and a Clip gives position and stop for free.
 * WAV needs no decode step, so `play` is a straight open-and-start.
 */
actual class AudioPlayer {

    private var clip: javax.sound.sampled.Clip? = null
    private var watcher: Thread? = null

    @Volatile
    actual var isPlaying: Boolean = false
        private set

    @Volatile
    actual var positionSeconds: Float = 0f
        private set

    actual fun play(bytes: ByteArray, mimeType: String, onEnded: () -> Unit) {
        stop()
        val stream = runCatching {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
        }.getOrNull()
        if (stream == null) {
            onEnded()
            return
        }

        val newClip = runCatching { AudioSystem.getClip() }.getOrNull()
        if (newClip == null) {
            onEnded()
            return
        }
        if (runCatching { newClip.open(stream) }.isFailure) {
            onEnded()
            return
        }

        clip = newClip
        positionSeconds = 0f
        isPlaying = true
        newClip.start()

        watcher = thread(name = "voice-playback", isDaemon = true) {
            while (isPlaying && newClip.isRunning) {
                positionSeconds = newClip.microsecondPosition / 1_000_000f
                Thread.sleep(50)
            }
            isPlaying = false
            positionSeconds = 0f
            runCatching { newClip.close() }
            onEnded()
        }
    }

    actual fun stop() {
        isPlaying = false
        runCatching { clip?.stop() }
        runCatching { clip?.close() }
        clip = null
        watcher = null
        positionSeconds = 0f
    }
}

/** A minimal RIFF/WAVE header around 16-bit mono PCM. Forty-four bytes, no dependencies. */
internal object Wav {
    fun wrap(pcm: ByteArray, sampleRate: Int): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun le(value: Int, bytes: Int) = (0 until bytes).forEach { out.write((value ushr (8 * it)) and 0xFF) }

        out.write("RIFF".toByteArray())
        le(36 + pcm.size, 4)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        le(16, 4)                 // PCM chunk size
        le(1, 2)                  // format = PCM
        le(1, 2)                  // channels
        le(sampleRate, 4)
        le(sampleRate * 2, 4)     // byte rate
        le(2, 2)                  // block align
        le(16, 2)                 // bits per sample
        out.write("data".toByteArray())
        le(pcm.size, 4)
        out.write(pcm)
        return out.toByteArray()
    }
}

/**
 * PCM → peaks, 0..100.
 *
 * Bucketed to at most 256 values because that is what the server stores (`smallint[]`, capped
 * at 256 in `finalizeUpload`), and because more bars than the widget has pixels is paying for
 * detail nobody can see.
 */
internal object Peaks {
    fun fromPcm(pcm: ByteArray, maxBuckets: Int = 256): List<Int> {
        if (pcm.size < 2) return emptyList()
        val sampleCount = pcm.size / 2
        val buckets = maxBuckets.coerceAtMost(sampleCount).coerceAtLeast(1)
        val perBucket = (sampleCount / buckets).coerceAtLeast(1)
        return (0 until buckets).map { b ->
            var peak = 0
            val end = ((b + 1) * perBucket).coerceAtMost(sampleCount)
            var i = b * perBucket
            while (i < end) {
                val low = pcm[i * 2].toInt() and 0xFF
                val high = pcm[i * 2 + 1].toInt()   // signed, little-endian
                val magnitude = abs((high shl 8) or low)
                if (magnitude > peak) peak = magnitude
                i++
            }
            (peak * 100 / 32768).coerceIn(0, 100)
        }
    }
}
