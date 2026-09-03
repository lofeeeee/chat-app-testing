package app.singular.client.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Swing's file chooser.
 *
 * Compose Desktop runs on the AWT event loop, so this hops onto the IO dispatcher to read the
 * file — a large read on the UI thread freezes the window mid-dialog, which reads as a crash.
 */
actual suspend fun pickFile(imagesOnly: Boolean): PickedFile? = withContext(Dispatchers.IO) {
    val chooser = JFileChooser().apply {
        dialogTitle = if (imagesOnly) "Choose an image" else "Choose a file"
        isMultiSelectionEnabled = false
        if (imagesOnly) {
            fileFilter = FileNameExtensionFilter(
                "Images", "png", "jpg", "jpeg", "gif", "webp", "heic",
            )
        }
    }

    if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) return@withContext null

    val file: File = chooser.selectedFile ?: return@withContext null
    if (!file.isFile || !file.canRead()) return@withContext null

    PickedFile(
        name = file.name,
        contentType = guessContentType(file.name),
        bytes = file.readBytes(),
    )
}
