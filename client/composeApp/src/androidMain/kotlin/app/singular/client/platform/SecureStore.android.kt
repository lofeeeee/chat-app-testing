package app.singular.client.platform

import android.content.Context
import app.singular.client.SingularApp

/**
 * Android secret storage.
 *
 * Private-mode SharedPreferences: the file lives in the app's own sandbox, readable only by
 * this UID. On a non-rooted device that is a stronger boundary than anything the desktop can
 * offer, because the OS enforces it per-app rather than per-user.
 *
 * The phase-5 upgrade is EncryptedSharedPreferences backed by the Android Keystore, which adds
 * at-rest encryption with a key the app cannot itself export — relevant against a rooted
 * device or a backup extraction, not against this app's own process.
 */
private const val PREFS = "singular_secrets"

private fun prefs() =
    SingularApp.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

actual fun readSecret(key: String): String? =
    runCatching { prefs().getString(key, null) }.getOrNull()

actual fun writeSecret(key: String, value: String) {
    runCatching { prefs().edit().putString(key, value).apply() }
}

actual fun clearSecret(key: String) {
    runCatching { prefs().edit().remove(key).apply() }
}
