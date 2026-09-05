package app.singular.client.platform

import android.content.Context
import app.singular.client.SingularApp

/**
 * Android actual: SharedPreferences under the app's private store.
 *
 * SharedPreferences rather than DataStore because the payload is one short string written on
 * emoji taps — the asynchronous machinery of DataStore buys nothing here and adds a coroutine
 * dependency to a synchronous `expect fun`.
 */
private object LocalStore {
    private const val PREFS = "singular_local"

    fun read(key: String): String? =
        SingularApp.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)

    fun write(key: String, value: String) {
        SingularApp.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
    }
}

actual fun readLocalString(key: String): String? = LocalStore.read(key)

actual fun writeLocalString(key: String, value: String) = LocalStore.write(key, value)
