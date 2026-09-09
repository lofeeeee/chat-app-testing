package app.singular.client.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Android: touch lists scroll with the finger; a scrollbar would be noise. */
@Composable
actual fun PlatformScrollbar(
    state: LazyListState,
    modifier: Modifier,
) = Unit
