package tech.mmarca.openwatch.features.home


import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.features.scales.ScalesSectionRoute
import tech.mmarca.openwatch.features.watches.WatchesSectionRoute
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.SectionHeader
import tech.mmarca.openwatch.ui.components.formatDeviceTime
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The Home destination: the only place that knows the ViewModel. It fills [HomeScreen]'s
 * watch and scale slots with their section routes, the same ones their own screens use.
 */
@Composable
fun HomeRoute(
    onOpenWatch: (String) -> Unit,
    onOpenScale: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val tile by viewModel.watchTile.collectAsStateWithLifecycle()

    HomeScreen(
        tile = tile,
        onSyncWatch = viewModel::syncWatch,
        onOpenWatch = onOpenWatch,
        onOpenHealthConnect = onOpenHealthConnect,
        onOpenSettings = onOpenSettings,
        watchesSection = { WatchesSectionRoute(onOpenWatch = onOpenWatch) },
        scalesSection = { ScalesSectionRoute(onOpenScale = onOpenScale) },
    )
}

/**
 * The device list: the watch tile, the paired watches with the add flow, the
 * scale with its add flow, then Health Connect and Settings. The watch and scale
 * sections come in as [watchesSection] and [scalesSection].
 */
@Composable
fun HomeScreen(
    tile: WatchTileState?,
    onSyncWatch: (deviceId: String) -> Unit,
    onOpenWatch: (String) -> Unit,
    onOpenHealthConnect: () -> Unit,
    onOpenSettings: () -> Unit,
    watchesSection: @Composable () -> Unit,
    scalesSection: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        tile?.let { watch ->
            WatchTile(
                tile = watch,
                onSync = { onSyncWatch(watch.deviceId) },
                onOpen = { onOpenWatch(watch.deviceId) },
                modifier = Modifier.padding(horizontal = Spacing.lg),
            )
        }
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
            tile = WatchTileState(
                deviceId = "forerunner-265",
                name = "Forerunner 265",
                batteryPercent = 78,
                lastSyncedAt = Instant.now().minusSeconds(40 * 60),
                additionalCount = 1,
                isSyncing = true,
                liveHeartRateBpm = 64,
                liveSteps = 6_412,
            ),
            onSyncWatch = {},
            onOpenWatch = {},
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

/** The one card that summarises the wrist: battery, last sync, live values, and a sync button. */
@Composable
internal fun WatchTile(
    tile: WatchTileState,
    onSync: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OpenWatchCard(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(Spacing.lg),
        ) {
            Icon(imageVector = Icons.Outlined.Watch, contentDescription = null, modifier = Modifier.size(HomeRowIconSize))
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                val name = if (tile.additionalCount > 0) {
                    pluralStringResource(R.plurals.home_watch_more, tile.additionalCount, tile.name, tile.additionalCount)
                } else {
                    tile.name
                }
                Text(text = name, style = MaterialTheme.typography.titleSmall)
                watchTileSubtitle(tile)?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (tile.isSyncing) {
                // In the button's 48 dp slot, so the row does not move when the sync starts.
                Box(contentAlignment = Alignment.Center, modifier = Modifier.minimumInteractiveComponentSize()) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(WatchSyncSpinnerSize),
                        strokeWidth = WatchSyncSpinnerStroke,
                    )
                }
            } else {
                IconButton(onClick = onSync) {
                    Icon(
                        imageVector = Icons.Outlined.Sync,
                        contentDescription = stringResource(R.string.home_watch_sync),
                        modifier = Modifier.size(WatchSyncGlyphSize),
                    )
                }
            }
        }
    }
}

@Composable
private fun watchTileSubtitle(tile: WatchTileState): String? {
    val parts = buildList {
        tile.liveHeartRateBpm?.let { add(stringResource(R.string.home_watch_live_heart_rate, it)) }
        tile.liveSteps?.let { add(stringResource(R.string.home_watch_live_steps, it)) }
        tile.batteryPercent?.let { add(stringResource(R.string.home_watch_battery, it)) }
        add(
            tile.lastSyncedAt?.let { stringResource(R.string.settings_watch_last_synced, formatDeviceTime(it)) }
                ?: stringResource(R.string.settings_watch_never_synced),
        )
    }
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}

private val HomeRowIconSize = 28.dp
private val WatchSyncGlyphSize = 18.dp
private val WatchSyncSpinnerSize = 20.dp
private val WatchSyncSpinnerStroke = 2.dp
