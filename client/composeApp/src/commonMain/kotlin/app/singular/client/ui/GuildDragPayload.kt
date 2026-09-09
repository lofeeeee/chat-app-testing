package app.singular.client.ui

import androidx.compose.ui.draganddrop.DragAndDropTransferData

/**
 * Builds the payload for a rail drag.
 *
 * The drag never leaves the app (rail to rail), so the payload is a formality — the real
 * contract is the module-scoped `draggedGuildId` the drop targets read. But the cross-platform
 * API still needs a [DragAndDropTransferData], and its transferable type is platform-specific:
 * AWT on desktop, a different model on Android. So the construction is `expect`/`actual` and
 * the only thing both sides agree on is the id it carries.
 */
expect fun guildDragPayload(guildId: String): DragAndDropTransferData
