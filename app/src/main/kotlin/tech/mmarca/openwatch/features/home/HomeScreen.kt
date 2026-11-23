package tech.mmarca.openwatch.features.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.ScreenColumn
import tech.mmarca.openwatch.ui.components.SectionHeader
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/** The detail side of the list-detail layout before anything is opened from the list. */
@Composable
fun HomeDetailPlaceholder(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xxl),
    ) {
        Icon(
            imageVector = Icons.Outlined.Watch,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(PlaceholderIconSize),
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = stringResource(R.string.home_detail_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The device list: the paired watches with the add flow, the scale with its add flow, the
 * devices every other family pairs through the app's own flow, then Health Connect and
 * Settings. The sections come in as [watchesSection], [scalesSection] and [devicesSection],
 * which brings its own header and draws nothing while no family pairs that way.
 */
@Composable
fun HomeScreen(
    onOpenHealthConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    watchesSection: @Composable () -> Unit,
    scalesSection: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    devicesSection: @Composable () -> Unit = {},
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
        devicesSection()
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
            devicesSection = { PreviewSlot("Other devices section") },
        )
    }
}

@Preview(showBackground = true, widthDp = 600)
@Composable
private fun HomeDetailPlaceholderPreview() {
    OpenWatchTheme {
        HomeDetailPlaceholder()
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
private val PlaceholderIconSize = 48.dp
