package tech.mmarca.openwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.mmarca.openwatch.core.presentation.DisplayValue
import tech.mmarca.openwatch.ui.theme.accentSurfaceContainerColor

val OpenWatchCardHorizontalPadding: Dp = 16.dp
val OpenWatchSectionSpacing: Dp = 8.dp
val OpenWatchMetricTilePadding: Dp = 12.dp
val OpenWatchMetricTileSpacing: Dp = 8.dp
val OpenWatchActionRowHeight: Dp = 48.dp
val OpenWatchSurfacePadding: PaddingValues = PaddingValues(12.dp)
val OpenWatchStandardRowSpacing: Dp = 8.dp

enum class OpenWatchCardStyle {
    Neutral,
    Metric,
    Accent,
    Error,
}

enum class OpenWatchSurfaceStyle {
    Neutral,
    Metric,
    Accent,
    Error,
    Warning,
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
fun OpenWatchSurface(
    modifier: Modifier = Modifier,
    style: OpenWatchSurfaceStyle = OpenWatchSurfaceStyle.Neutral,
    containerColor: Color = Color.Unspecified,
    contentColor: Color = Color.Unspecified,
    shape: Shape = MaterialTheme.shapes.medium,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    val resolvedContainerColor = when {
        containerColor != Color.Unspecified -> containerColor
        style == OpenWatchSurfaceStyle.Metric -> MaterialTheme.colorScheme.surfaceContainerHighest
        style == OpenWatchSurfaceStyle.Accent && contentColor != Color.Unspecified ->
            accentSurfaceContainerColor(
                accentColor = contentColor,
                amoledAlpha = 0.09f,
            )
        style == OpenWatchSurfaceStyle.Warning ->
            accentSurfaceContainerColor(
                accentColor = MaterialTheme.colorScheme.error,
                amoledAlpha = 0.12f,
            )
        style == OpenWatchSurfaceStyle.Error -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.surfaceContainer
    }

    val resolvedContentColor = when {
        contentColor != Color.Unspecified -> contentColor
        style == OpenWatchSurfaceStyle.Error -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Surface(
        modifier = modifier,
        shape = shape,
        color = resolvedContainerColor,
        contentColor = resolvedContentColor,
        border = border,
    ) {
        if (contentPadding != PaddingValues(0.dp)) {
            Column(
                modifier = Modifier
                    .padding(contentPadding),
                content = { content() },
            )
        } else {
            content()
        }
    }
}

@Composable
fun DetailSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    OpenWatchCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(OpenWatchCardHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(OpenWatchSectionSpacing),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
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
fun OpenWatchFilledButton(
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
        style = OpenWatchButtonStyle.Filled,
        content = content,
    )
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
    val interactionEnabled = enabled && !false
    IconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        enabled = interactionEnabled,
    ) {
        content()
    }
}

@Composable
fun OpenWatchIconSurfaceButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    content: @Composable () -> Unit,
) {
    val interactionEnabled = enabled && !false
    OpenWatchSurface(
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .clickable(enabled = interactionEnabled, onClick = onClick),
        containerColor = if (interactionEnabled) {
            containerColor
        } else {
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.55f)
        },
        contentColor = if (interactionEnabled) {
            contentColor
        } else {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        },
        shape = CircleShape,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.42f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(0.58f),
        )
    }
}

@Composable
fun CompactHeadingText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

@Composable
fun OpenWatchTitleValueRow(
    title: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    titleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = titleColor,
        )
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
            )
            if (unit.isNotBlank()) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = titleColor,
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
        }
    }
}

@Composable
fun CompactBodyText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

@Composable
fun AccentIconChip(
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    iconSize: Dp = 16.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun SoftProgressBand(
    progress: () -> Float,
    trackColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier,
    trackAlpha: Float = 0.3f,
) {
    Box(modifier = modifier) {
        LinearProgressIndicator(
            progress = progress,
            trackColor = trackColor.copy(alpha = trackAlpha),
            color = fillColor,
        )
    }
}

@Composable
fun SharedMetricTile(
    title: String,
    value: DisplayValue,
    modifier: Modifier = Modifier,
) {
    OpenWatchCard(
        modifier = modifier,
        style = OpenWatchCardStyle.Metric,
    ) {
        Column(modifier = Modifier.padding(OpenWatchMetricTilePadding)) {
            AutoResizeText(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
            Spacer(Modifier.height(OpenWatchMetricTileSpacing))
            Row(verticalAlignment = Alignment.Bottom) {
                AutoResizeText(
                    text = value.value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
                if (value.unit.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = value.unit,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
        }
    }
}
