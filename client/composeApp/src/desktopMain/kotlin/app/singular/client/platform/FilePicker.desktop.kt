package app.singular.client.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.EventQueue
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * The operating system's own file picker.
 *
 * `java.awt.FileDialog`, not Swing's `JFileChooser`. The distinction is the whole point:
 * `JFileChooser` is drawn by Java and looks like Java — no Quick access sidebar, no OneDrive,
 * no recent places, none of the shell integration people expect. `FileDialog` is a thin
 * wrapper over the platform's own dialog: `GetOpenFileName` on Windows, `NSOpenPanel` on
 * macOS. Same class, native result, and no dependency — which matters here, where every
 * library is vendored offline.
 *
 * Returns null when the user cancels. Cancelling is not an error and shouldn't surface one.
 */
actual suspend fun pickFile(imagesOnly: Boolean): PickedFile? = withContext(Dispatchers.IO) {
    val file = onEventThread { showNativeDialog(imagesOnly) } ?: return@withContext null
    if (!file.isFile || !file.canRead()) return@withContext null

    // Read on the IO dispatcher, deliberately off the event thread. Compose Desktop runs on
    // the AWT event loop, and a hundred-megabyte read there freezes the window — which reads
    // as a crash, not as work in progress.
    PickedFile(
        name = file.name,
        contentType = guessContentType(file.name),
        bytes = file.readBytes(),
    )
}

/**
 * Opens the dialog. Must run on the AWT event thread — it is a native modal window.
 *
 * The image filter is set twice on purpose, because the two platforms read different things:
 * Windows ignores [FileDialog.setFilenameFilter] entirely and takes a semicolon-separated
 * wildcard list in `file`, while macOS and Linux ignore the wildcard and use the callback.
 * Setting only one of them gives you an unfiltered dialog on the other platform.
 */
private fun showNativeDialog(imagesOnly: Boolean): File? {
    val dialog = FileDialog(
        null as Frame?,
        if (imagesOnly) "Choose an image" else "Choose a file",
        FileDialog.LOAD,
    )

    dialog.isMultipleMode = false

    if (imagesOnly) {
        dialog.file = IMAGE_EXTENSIONS.joinToString(";") { "*.$it" }
        dialog.setFilenameFilter { _, name ->
            name.substringAfterLast('.', "").lowercase() in IMAGE_EXTENSIONS
        }
    }

    dialog.isVisible = true   // blocks until the user picks or cancels

    // `directory` and `file` are both null on cancel. Reading `files` instead would hand back
    // an empty array and hide which of the two happened.
    val directory = dialog.directory ?: return null
    val name = dialog.file ?: return null
    return File(directory, name)
}

/**
 * Runs [block] on the AWT event thread and waits for its result.
 *
 * `invokeAndWait` throws if it is already on the event thread, so the check is not optional —
 * and this is genuinely reachable, since Compose Desktop's own coroutine scope dispatches
 * there.
 */
private fun <T> onEventThread(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()

    var result: T? = null
    var failure: Throwable? = null
    EventQueue.invokeAndWait {
        runCatching(block).fold(onSuccess = { result = it }, onFailure = { failure = it })
    }
    failure?.let { throw it }

    @Suppress("UNCHECKED_CAST")
    return result as T
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "heic", "bmp")
