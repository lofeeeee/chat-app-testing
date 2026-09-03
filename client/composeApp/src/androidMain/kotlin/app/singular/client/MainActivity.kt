package app.singular.client

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import app.singular.client.platform.FilePickerBridge
import java.util.UUID

class SingularApp : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var appContext: Context
            private set
    }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Result launchers must be registered before the Activity reaches STARTED, which is
        // long before anyone taps "attach". The bridge parks the suspending picker on a
        // deferred that this callback completes.
        val launcher = registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { result ->
            FilePickerBridge.current()?.complete(result.data?.data)
        }
        FilePickerBridge.attach(FilePickerBridge.Holder(launcher, contentResolver))
        setContent {
            // The emulator reaches the host machine's localhost at 10.0.2.2.
            App(
                httpUrl = "http://10.0.2.2:8080/graphql",
                wsUrl = "ws://10.0.2.2:8080/graphql",
            )
        }
    }

    override fun onDestroy() {
        // Holds a ContentResolver tied to this Activity; leaving it attached across a
        // configuration change leaks the whole Activity.
        FilePickerBridge.detach()
        super.onDestroy()
    }
}

actual fun deviceId(): String = DeviceIdStore.current()

/**
 * Android install id.
 *
 * Explicitly not a MAC address: Android 10 and later return a fixed `02:00:00:00:00:00` for
 * every app, so the value carries no information at all. A UUID generated on first launch is
 * what actually identifies an install.
 *
 * Phase 5 moves this into EncryptedSharedPreferences backed by the Android Keystore, so it
 * survives backup/restore correctly and can't be read by another app on a rooted device.
 */
private object DeviceIdStore {
    private const val PREFS = "singular"
    private const val KEY = "device_id"

    fun current(): String {
        val prefs = SingularApp.appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY, null)?.let { return it }

        val fresh = UUID.randomUUID().toString()
        prefs.edit().putString(KEY, fresh).apply()
        return fresh
    }
}

/** e.g. "Pixel 8 (Android 15)". Recognisable at a glance in the sessions list. */
actual val platformName: String =
    "${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE})"
