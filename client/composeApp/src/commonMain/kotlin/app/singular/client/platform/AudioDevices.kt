package app.singular.client.platform

/**
 * Choosing which microphone records and which speakers play.
 *
 * ## Why the choice lives on the machine
 *
 * A device is identified by its name — "Microphone Array (Realtek(R) Audio)" — and that name
 * means nothing on another computer. Syncing it through `updateSettings` would push a headset
 * that only exists on one desk onto every device the account signs in on, so this is stored
 * locally, for the same reason the notification switches are: it is a property of where you are
 * sitting, not of who you are.
 *
 * ## Why names rather than indices
 *
 * `AudioSystem` hands out mixers in an order that changes the moment something is plugged in or
 * removed, so an index saved today points at a different device tomorrow. A name survives that,
 * and when the named device genuinely isn't there the platform layer falls back to the system
 * default rather than failing — an unplugged headset must not break recording.
 *
 * Plain `expect fun`s rather than an `expect object`: expect classes and objects are still Beta
 * in Kotlin and warn on every build, which is why [readLocalString] is shaped this way too.
 */
data class AudioDevice(
    /** Stable across restarts, and what gets persisted. The platform's own name for the device. */
    val id: String,
    /** What to show. The same as [id] today; separate because it needn't stay that way. */
    val label: String,
)

/**
 * Microphones this machine can record from.
 *
 * Empty means the platform doesn't offer a choice at all, which the UI says plainly rather than
 * showing an empty menu. Touches the audio subsystem, so call it off the UI thread.
 */
expect fun inputDevices(): List<AudioDevice>

/** Speakers this machine can play through. Empty means no choice; see [inputDevices]. */
expect fun outputDevices(): List<AudioDevice>

/**
 * The saved device choices.
 *
 * Read by the platform audio code at the moment it opens a line, which is why it lives down here
 * rather than in `AppState`: capture and playback sit below the app layer and cannot reach up
 * into it. `AppState` mirrors these into observable properties for the settings screen.
 *
 * null means "whatever the system picked", which is both the default and the fallback.
 */
object AudioDeviceChoice {

    var input: String?
        get() = readLocalString(INPUT_KEY)?.ifBlank { null }
        set(value) = writeLocalString(INPUT_KEY, value.orEmpty())

    var output: String?
        get() = readLocalString(OUTPUT_KEY)?.ifBlank { null }
        set(value) = writeLocalString(OUTPUT_KEY, value.orEmpty())

    private const val INPUT_KEY = "audio_input_device"
    private const val OUTPUT_KEY = "audio_output_device"
}
