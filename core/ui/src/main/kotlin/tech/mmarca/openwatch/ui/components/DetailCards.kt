package tech.mmarca.openwatch.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import tech.mmarca.openwatch.ui.theme.accentSurfaceContainerColor

enum class OpenWatchCardStyle {
    Neutral,
    Metric,
    Accent,
    Error,
}

enum class OpenWatchButtonStyle {
    Filled,
    Tonal,
    Outlined,
    Text,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenWatchCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = Color.Unspecified,
    style: OpenWatchCardStyle = OpenWatchCardStyle.Neutral,
    accentColor: Color = Color.Unspecified,
    shape: Shape = MaterialTheme.shapes.medium,
    elevation: CardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    content: @Composable () -> Unit,
) {
    val resolvedContainerColor = when {
        containerColor != Color.Unspecified -> containerColor
        style == OpenWatchCardStyle.Metric -> MaterialTheme.colorScheme.surfaceContainerHighest
        style == OpenWatchCardStyle.Accent && accentColor != Color.Unspecified ->
            accentSurfaceContainerColor(accentColor, amoledAlpha = 0.09f)
        style == OpenWatchCardStyle.Error -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }

    val colors = CardDefaults.cardColors(containerColor = resolvedContainerColor)
    val effectiveOnClick = onClick?.takeUnless { false }

    if (effectiveOnClick != null) {
        Card(
            onClick = effectiveOnClick,
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
        ) {
            content()
        }
    } else {
        Card(
            modifier = modifier,
            shape = shape,
            colors = colors,
            elevation = elevation,
        ) {
            content()
        }
    }
}

@Composable
fun OpenWatchButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    border: BorderStroke? = null,
    shape: Shape = CircleShape,
    style: OpenWatchButtonStyle = OpenWatchButtonStyle.Filled,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionEnabled = enabled && !false
    when (style) {
        OpenWatchButtonStyle.Filled -> Button(
            onClick = onClick,
            modifier = modifier,
            enabled = interactionEnabled,
            colors = colors ?: ButtonDefaults.buttonColors(),
            contentPadding = contentPadding,
            shape = shape,
        ) {
            content()
        }
        OpenWatchButtonStyle.Tonal -> FilledTonalButton(
            onClick = onClick,
            modifier = modifier,
            enabled = interactionEnabled,
            colors = colors ?: ButtonDefaults.filledTonalButtonColors(),
            contentPadding = contentPadding,
            shape = shape,
        ) {
            content()
        }
        OpenWatchButtonStyle.Outlined -> OutlinedButton(
            onClick = onClick,
            modifier = modifier,
            enabled = interactionEnabled,
            colors = colors ?: ButtonDefaults.outlinedButtonColors(),
            // Null here would draw no outline at all, so a caller without its own border gets Material's.
            border = border ?: ButtonDefaults.outlinedButtonBorder(interactionEnabled),
            contentPadding = contentPadding,
            shape = shape,
        ) {
            content()
        }
        OpenWatchButtonStyle.Text -> TextButton(
            onClick = onClick,
            modifier = modifier,
            enabled = interactionEnabled,
            colors = colors ?: ButtonDefaults.textButtonColors(),
            contentPadding = contentPadding,
            shape = shape,
        ) {
            content()
        }
    }
}

@Composable
fun OpenWatchTonalButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    buttonColors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    border: BorderStroke? = null,
    content: @Composable RowScope.() -> Unit,
) {
    OpenWatchButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = buttonColors,
        contentPadding = contentPadding,
        border = border,
        style = OpenWatchButtonStyle.Tonal,
        content = content,
    )
}

@Composable
fun OpenWatchOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    buttonColors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    border: BorderStroke? = null,
    content: @Composable RowScope.() -> Unit,
) {
    OpenWatchButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = buttonColors,
        contentPadding = contentPadding,
        border = border,
        style = OpenWatchButtonStyle.Outlined,
        content = content,
    )
}

@Composable
fun OpenWatchTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    buttonColors: ButtonColors? = null,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
    border: BorderStroke? = null,
    content: @Composable RowScope.() -> Unit,
) {
    OpenWatchButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = buttonColors,
        contentPadding = contentPadding,
        border = border,
        style = OpenWatchButtonStyle.Text,
        content = content,
    )
}

@Composable
fun OpenWatchIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    // The platform's 48 dp touch target, not a smaller one.
    IconButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        content()
    }
}
