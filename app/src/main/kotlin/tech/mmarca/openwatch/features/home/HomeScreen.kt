package tech.mmarca.openwatch.features.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.features.scales.ScalesSectionRoute
import tech.mmarca.openwatch.features.watches.WatchesSectionRoute
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.ScreenColumn
import tech.mmarca.openwatch.ui.components.SectionHeader
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The Home destination. It has no state of its own: it fills [HomeScreen]'s watch and scale
 * slots with their section routes, the same ones their own screens use.
 */
@Composable
fun HomeRoute(
    onOpenWatch: (String) -> Unit,
    onOpenScale: (deviceId: String) -> Unit,
    onOpenHealthConnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    HomeScreen(
        onOpenHealthConnect = onOpenHealthConnect,
        onOpenSettings = onOpenSettings,
        watchesSection = { WatchesSectionRoute(onOpenWatch = onOpenWatch) },
        scalesSection = { ScalesSectionRoute(onOpenScale = onOpenScale) },
    )
}

/**
 * The device list: the paired watches with the add flow, the scale with its add flow, then
 * Health Connect and Settings. The watch and scale sections come in as [watchesSection] and
 * [scalesSection].
 */
@Composable
fun HomeScreen(
    onOpenHealthConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    watchesSection: @Composable () -> Unit,
    scalesSection: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    ScreenColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        horizontalPadding = 0.dp,
    ) {
        SectionHeader(stringResource(R.string.home_section_watches))
        watchesSection()
        SectionHeader(stringResource(R.string.home_section_scale))
        scalesSection()
        SectionHeader(stringResource(R.string.home_section_app))
        HomeRow(
            icon = Icons.Outlined.FavoriteBorder,
            title = stringResource(R.string.home_health_connect),
            subtitle = stringResource(R.string.home_health_connect_body),
            onClick = onOpenHealthConnect,
        )
        HomeRow(
            icon = Icons.Outlined.Settings,
            title = stringResource(R.string.home_settings),
            subtitle = stringResource(R.string.home_settings_body),
            onClick = onOpenSettings,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeScreenPreview() {
    OpenWatchTheme {
        HomeScreen(
            onOpenHealthConnect = {},
            onOpenSettings = {},
            watchesSection = { PreviewSlot("Watches section") },
            scalesSection = { PreviewSlot("Scale section") },
        )
    }
}

/** Stands in for a section route, which needs its ViewModel. */
@Composable
private fun PreviewSlot(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = Spacing.lg),
    )
}

@Composable
private fun HomeRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    OpenWatchCard(modifier = Modifier.padding(horizontal = Spacing.lg)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(Spacing.lg),
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(HomeRowIconSize))
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        }
    }
}

private val HomeRowIconSize = 28.dp
