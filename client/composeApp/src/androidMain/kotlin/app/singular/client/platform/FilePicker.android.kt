package app.singular.client.platform

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.result.ActivityResultLauncher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Android's document picker.
 *
 * Android hands results back through an Activity callback rather than returning them, so the
 * launcher has to be registered while the Activity is being created — long before anyone picks
 * anything. [FilePickerBridge] is what spans that gap: the Activity registers itself on start,
 * and this suspending function parks on a deferred until the callback fires.
 *
 * `ACTION_OPEN_DOCUMENT` rather than `GET_CONTENT` because it returns a stable, permissioned
 * URI instead of a one-shot handle that can expire before the upload finishes.
 */
actual suspend fun pickFile(imagesOnly: Boolean): PickedFile? {
    val bridge = FilePickerBridge.current()
        ?: return null   // no Activity attached; nothing to show a dialog on

    val uri = bridge.launch(imagesOnly) ?: return null

    return withContext(Dispatchers.IO) {
        val resolver = bridge.contentResolver ?: return@withContext null

        val name = resolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        } ?: "file"

        val bytes = runCatching {
            resolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull() ?: return@withContext null

        PickedFile(
            name = name,
            // The system's own type is more reliable than guessing from the extension, which a
            // content:// URI may not even have.
            contentType = resolver.getType(uri) ?: guessContentType(name),
            bytes = bytes,
        )
    }
}

/**
 * Connects the Activity's result launcher to the suspending [pickFile].
 *
 * MainActivity registers itself in onCreate and clears it in onDestroy; without that, picking a
 * file simply returns null rather than crashing.
 */
object FilePickerBridge {

    private val instance = AtomicReference<Holder?>(null)

    class Holder(
        val launcher: ActivityResultLauncher<Intent>,
        val contentResolver: android.content.ContentResolver,
    ) {
        internal var pending: CompletableDeferred<Uri?>? = null

        suspend fun launch(imagesOnly: Boolean): Uri? {
            val deferred = CompletableDeferred<Uri?>()
            pending = deferred

            launcher.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = if (imagesOnly) "image/*" else "*/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            )
            return deferred.await()
        }

        /** Called from the Activity's result callback. */
        fun complete(uri: Uri?) {
            pending?.complete(uri)
            pending = null
        }
    }

    fun attach(holder: Holder) = instance.set(holder)
    fun detach() = instance.set(null)
    fun current(): Holder? = instance.get()
}
