package app.singular.client.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable
import java.awt.datatransfer.StringSelection

/**
 * Desktop (AWT): a plain-text transferable carrying the guild id.
 *
 * `supportedActions` is explicit and Move-only: this is a reorder gesture, not a copy, and
 * leaving the default would let the source render as though the tile could be "dropped into"
 * other apps. Both opt-in markers are listed because the constructor surface spans them in
 * this Compose version; an unused marker is a warning, a missing one is an error.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
actual fun guildDragPayload(guildId: String): DragAndDropTransferData =
    DragAndDropTransferData(
        transferable = DragAndDropTransferable(StringSelection(guildId)),
        supportedActions = listOf(DragAndDropTransferAction.Move),
        onTransferCompleted = { },
    )
