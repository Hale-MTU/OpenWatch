package tech.mmarca.openwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import tech.mmarca.openwatch.ui.theme.Spacing

/** The pieces every paired-device screen shares: the round glyph, the list row, the value row, the time. */

/**
 * A paired device in a list: its glyph, its [name], a [status] line and a [detail] line (when
 * it last synced, or weighed), opening the device on a tap. [selected] marks the one shown
 * beside the list on a wide screen.
 */
@Composable
fun DeviceListRow(
    name: String,
    status: String,
    detail: String,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    /** The device's kind; a watch when null. */
    icon: ImageVector? = null,
    selected: Boolean = false,
) {
    // An outline, not a fill: a filled container hides the avatar's circle in some palettes and
    // takes the contrast from the status lines.
    val shape = MaterialTheme.shapes.medium
    OpenWatchCard(
        modifier = if (selected) {
            modifier.border(SelectedOutlineWidth, MaterialTheme.colorScheme.primary, shape)
        } else {
            modifier
        },
        shape = shape,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { this.selected = selected }
                .clickable(onClick = onOpen)
                .padding(Spacing.lg),
        ) {
            DeviceAvatar(icon = icon)
            Spacer(modifier = Modifier.width(DeviceRowIconGap))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, style = MaterialTheme.typography.titleSmall)
                Text(text = status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The round glyph for a device. [icon] is the device's kind; a watch by default. */
@Composable
fun DeviceAvatar(
    size: Int = 40,
    icon: ImageVector? = null,
) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .background(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon ?: Icons.Outlined.Watch,
            contentDescription = null,
            modifier = Modifier.size((size * 0.55f).dp),
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** A label/value row for a stored device value, with optional supporting text. */
@Composable
fun DeviceValueRow(
    label: String,
    value: String,
    supporting: String? = null,
) {
    OpenWatchCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(modifier = Modifier.width(Spacing.lg))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

/** The gap between a device's glyph and its text, in every device row and header. */
val DeviceRowIconGap = 14.dp

private val SelectedOutlineWidth = 2.dp

/** A device timestamp: the time for today, the date once older. */
fun formatDeviceTime(
    at: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    today: LocalDate = LocalDate.now(),
): String {
    val local = at.atZone(zone)
    return if (local.toLocalDate() == today) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(local)
    } else {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(local)
    }
}
