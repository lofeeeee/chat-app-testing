package app.singular.client.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.draganddrop.DragAndDropTransferAction
import androidx.compose.ui.draganddrop.DragAndDropTransferData
import androidx.compose.ui.draganddrop.DragAndDropTransferable

/**
 * Android: the rail's drag-and-drop is in-process only (rail to rail within the app), and the
 * real contract is the module-scoped `draggedGuildId`. The platform transferable is unused on
 * this path, so a plain-text payload is enough to satisfy the API.
 *
 * `supportedActions` is Move-only for the same reason as the desktop actual: a reorder
 * gesture must not advertise itself as a copy source to anything else on the device.
 */
@OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
actual fun guildDragPayload(guildId: String): DragAndDropTransferData =
    DragAndDropTransferData(
        transferable = DragAndDropTransferable(
            android.content.ClipData.newPlainText("guild", guildId)
        ),
        supportedActions = listOf(DragAndDropTransferAction.Move),
        onTransferCompleted = { },
    )
