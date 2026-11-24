package tech.mmarca.openwatch.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.mmarca.openwatch.ui.theme.Spacing

/** How wide a screen's content may grow: a phone uses all of it, a tablet or an unfolded phone a readable column. */
val ScreenMaxWidth: Dp = 640.dp

/**
 * The body of a scrolling screen: the side gutter, the gap between cards, a maximum width
 * centred on a large screen, and room for the keyboard, so a form's field is not hidden
 * behind it. Every screen that scrolls one column uses it, instead of its own copy.
 */
@Composable
fun ScreenColumn(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(Spacing.md),
    horizontalPadding: Dp = Spacing.lg,
    verticalPadding: Dp = Spacing.md,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        contentAlignment = Alignment.TopCenter,
        modifier = modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        Column(
            verticalArrangement = verticalArrangement,
            modifier = Modifier
                .widthIn(max = ScreenMaxWidth)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = horizontalPadding, vertical = verticalPadding),
            content = content,
        )
    }
}

/**
 * For a screen that lays out its own list (a `LazyColumn`): all of the screen, room for the
 * keyboard, and the same maximum width as [ScreenColumn], centred.
 */
fun Modifier.screenContent(): Modifier = this
    .fillMaxSize()
    .imePadding()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = ScreenMaxWidth)
    .fillMaxWidth()
