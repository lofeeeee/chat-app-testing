package app.singular.client.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A styled scrollbar for a [LazyListState], drawn at the trailing edge of its container.
 *
 * Desktop shows a thin, palette-coloured track that fades in on scroll and hover; on touch
 * platforms there is nothing — a scrollbar would be noise next to the list's own inertial
 * scrolling, and Android hides it.
 *
 * This is `expect`/`actual` rather than an `if` because `VerticalScrollbar` is a skiko-only
 * API — it isn't on the common classpath at all, so the desktop implementation has to live in
 * `desktopMain`. The common signature is deliberately the smallest thing both sides share: a
 * list state, not a scrollable modifier.
 */
@Composable
expect fun PlatformScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
)
