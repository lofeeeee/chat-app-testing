package app.singular.client.platform

/**
 * Android offers no choice here, and says so rather than pretending otherwise.
 *
 * `AudioManager.getDevices` could list endpoints, but *honouring* a selection needs
 * `setPreferredDevice` on the recorder and the player, which arrived in API 28 — above this
 * app's minimum of 26. Listing devices we would then ignore is worse than listing none: the
 * setting would look as though it worked and quietly do nothing.
 *
 * Android also routes voice audio itself, following the headset or speaker the user last chose
 * at the system level, which is the behaviour people expect from a phone anyway. Returning an
 * empty list makes the settings screen explain that instead of showing a dead menu.
 */
actual fun inputDevices(): List<AudioDevice> = emptyList()

actual fun outputDevices(): List<AudioDevice> = emptyList()
