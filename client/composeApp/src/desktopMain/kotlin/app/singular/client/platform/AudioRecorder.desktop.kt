package app.singular.client.platform

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
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
            // Resolved per take, not once at construction: choosing a different microphone in
            // settings should apply to the next thing you record, without a restart.
            val target = openMicrophone(format).also {
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
 * Opens the chosen microphone, or the system default.
 *
 * A device that is no longer connected falls back rather than throwing. Someone who unplugs a
 * headset should get their built-in microphone back, not a recorder that fails at the moment
 * they start speaking — and the setting is left alone, so the headset is used again when it
 * returns.
 */
private fun openMicrophone(format: AudioFormat): TargetDataLine {
    val chosen = mixerNamed(AudioDeviceChoice.input)
        ?: return AudioSystem.getTargetDataLine(format)
    return runCatching { AudioSystem.getTargetDataLine(format, chosen) }
        .getOrElse { AudioSystem.getTargetDataLine(format) }
}

/** Opens a clip on the chosen speakers, or the system default. Same fallback as the mic. */
private fun openClip(): javax.sound.sampled.Clip {
    val chosen = mixerNamed(AudioDeviceChoice.output) ?: return AudioSystem.getClip()
    return runCatching { AudioSystem.getClip(chosen) }.getOrElse { AudioSystem.getClip() }
}

/**
 * Desktop playback: a `Clip`, not a `SourceDataLine`.
 *
 * One short voice note doesn't need a stream, and a Clip gives position and stop for free.
 * WAV needs no decode step, so `play` is a straight open-and-start.
 */
actual class AudioPlayer {

    /**
     * One run of one clip.
     *
     * Each play gets its own, so a late event from a clip that has been superseded can be
     * recognised as stale rather than acted on: [cancelled] marks a stop we asked for, and
     * [finished] makes the end-of-playback bookkeeping run exactly once however it is reached.
     */
    private class Playback(val clip: javax.sound.sampled.Clip) {
        val finished = AtomicBoolean(false)

        @Volatile
        var cancelled = false
    }

    @Volatile
    private var current: Playback? = null

    private var watcher: Thread? = null

    // Private volatile backing fields, read-only `val` actuals over them.
    //
    // `actual var … private set` does not satisfy `expect val`: Kotlin matches property *kind*,
    // and a var with a private setter is still a var. Read-only is also the honest contract —
    // playback position is something the clip reports, not something a caller may assign.
    // Volatile because the watcher thread writes both and the UI thread polls them.
    @Volatile private var playing = false

    @Volatile private var position = 0f

    actual val isPlaying: Boolean get() = playing

    actual val positionSeconds: Float get() = position

    actual fun play(bytes: ByteArray, mimeType: String, onEnded: () -> Unit) {
        stop()
        val stream = runCatching {
            AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes))
        }.getOrNull()
        if (stream == null) {
            onEnded()
            return
        }

        val newClip = runCatching { openClip() }.getOrNull()
        if (newClip == null) {
            onEnded()
            return
        }
        if (runCatching { newClip.open(stream) }.isFailure) {
            onEnded()
            return
        }

        val playback = Playback(newClip)
        current = playback

        // End of playback is an event from the line, not something to poll for.
        //
        // `Clip.start()` is asynchronous: `isRunning` stays false for a moment after it
        // returns. The watcher below used to read `while (playing && clip.isRunning)`, so it
        // could see "not running" *before playback had begun*, treat that as the end, and close
        // the clip on the spot. The first note of a session won its race — opening the audio
        // device the first time is slow enough to cover the gap — and every replay afterwards
        // lost it, which is why a voice note played once and then never again. A STOP event is
        // the device saying it is done, so there is no race left to lose.
        newClip.addLineListener { event ->
            if (event.type != LineEvent.Type.STOP) return@addLineListener
            if (playback.cancelled) return@addLineListener
            if (!playback.finished.compareAndSet(false, true)) return@addLineListener

            // Only speak for the playback that is still current: a stale clip finishing must
            // not reset the position of the note that replaced it.
            if (current === playback) {
                playing = false
                position = 0f
                current = null
            }
            runCatching { newClip.close() }
            onEnded()
        }

        position = 0f
        playing = true
        newClip.start()

        // Position only — this thread no longer decides when playback is over.
        watcher = thread(name = "voice-playback", isDaemon = true) {
            while (current === playback && playing) {
                position = newClip.microsecondPosition / 1_000_000f
                Thread.sleep(50)
            }
        }
    }

    actual fun stop() {
        // A stop we asked for fires no `onEnded`. The caller already knows it stopped, and the
        // callback would otherwise land on whichever note it starts next.
        val playback = current
        playback?.cancelled = true
        playback?.finished?.set(true)
        current = null
        playing = false
        runCatching { playback?.clip?.stop() }
        runCatching { playback?.clip?.close() }
        watcher = null
        position = 0f
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
