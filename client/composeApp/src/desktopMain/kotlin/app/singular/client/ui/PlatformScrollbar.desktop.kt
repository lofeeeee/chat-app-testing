package app.singular.client.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

/**
 * The desktop scrollbar: a thin track, palette `line` colour, wider on hover.
 *
 * `LocalScrollbarStyle` is the only sane way to restyle every scrollbar the same way without
 * re-authoring each one — it is provided here (rather than at the app root) so its reach is
 * exactly this call site, not every future scrollbar in the app.
 */
@Composable
actual fun PlatformScrollbar(
    state: LazyListState,
    modifier: Modifier,
) {
    val style = LocalScrollbarStyle.current.copy(
        thickness = 8.dp,
        minimalHeight = 28.dp,
        unhoverColor = LocalSingularColors.current.line.copy(alpha = 0.35f),
        hoverColor = LocalSingularColors.current.lineStrong.copy(alpha = 0.75f),
    )
    CompositionLocalProvider(LocalScrollbarStyle provides style) {
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(state),
            modifier = modifier,
        )
    }
}
