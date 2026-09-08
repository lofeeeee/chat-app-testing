package app.singular.client.platform

import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Line
import javax.sound.sampled.Mixer
import javax.sound.sampled.SourceDataLine
import javax.sound.sampled.TargetDataLine

/**
 * Desktop devices, enumerated from `javax.sound.sampled`.
 *
 * A "mixer" here is the JDK's word for an endpoint, and the list it returns includes entries
 * that cannot carry audio at all — on Windows it reports `Port Speaker (…)` and
 * `Port Microphone Array (…)` alongside the real ones. Those are volume/mute control ports, so
 * asking each mixer whether it supports the *line type we actually need* is what separates a
 * device you can record from from a device you can only turn down.
 */
actual fun inputDevices(): List<AudioDevice> = devicesSupporting(TargetDataLine::class.java)

actual fun outputDevices(): List<AudioDevice> = devicesSupporting(SourceDataLine::class.java)

private fun devicesSupporting(lineType: Class<*>): List<AudioDevice> {
    val wanted = Line.Info(lineType)
    return AudioSystem.getMixerInfo()
        .mapNotNull { info ->
            val mixer = runCatching { AudioSystem.getMixer(info) }.getOrNull()
                ?: return@mapNotNull null
            if (!runCatching { mixer.isLineSupported(wanted) }.getOrDefault(false)) {
                return@mapNotNull null
            }
            if (info.name in PLATFORM_DEFAULT_ALIASES) return@mapNotNull null
            AudioDevice(id = info.name, label = info.name)
        }
        // Two mixers can report the same name. Since the name is the identity we persist, a
        // duplicate entry would be a menu row you cannot tell apart from the one above it.
        .distinctBy { it.id }
}

/**
 * DirectSound's aliases for "whatever Windows currently considers default".
 *
 * They aren't devices — picking one means the same thing as the menu's own "System default"
 * entry, so listing them offers a second way to say what the first entry already says, under a
 * name that reads like hardware.
 *
 * Matched by name because nothing else distinguishes them: `Primary Sound Driver` and
 * `Speaker (Realtek(R) Audio)` report the same vendor, the same version, the same description
 * (`Direct Audio Device: DirectSound Playback`) and the same provider class. A localised Windows
 * may well name them differently, in which case the alias simply appears in the list — harmless,
 * since selecting it still resolves to the default device.
 */
private val PLATFORM_DEFAULT_ALIASES = setOf(
    "Primary Sound Driver",
    "Primary Sound Capture Driver",
)

/**
 * The mixer with this name, or null when nothing matches.
 *
 * Null is the ordinary case, not an error: a device that was chosen last week may simply be
 * unplugged today, and every caller answers that by falling back to the system default.
 */
internal fun mixerNamed(name: String?): Mixer.Info? {
    if (name.isNullOrBlank()) return null
    return runCatching { AudioSystem.getMixerInfo().firstOrNull { it.name == name } }.getOrNull()
}
