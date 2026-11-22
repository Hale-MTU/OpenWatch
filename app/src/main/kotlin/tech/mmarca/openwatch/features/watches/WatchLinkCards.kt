package tech.mmarca.openwatch.features.watches

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.ui.components.SwitchCard

/**
 * The large-Bluetooth-packets switch: on by default, off for a watch that
 * drops the link or never gets a sync going once the packets grow.
 */
@Composable
internal fun HighMtuCard(
    state: WatchDeviceUiState,
    onToggle: (Boolean) -> Unit,
) {
    SwitchCard(
        title = stringResource(R.string.settings_watch_high_mtu),
        body = stringResource(R.string.settings_watch_high_mtu_body),
        checked = state.highMtu,
        onCheckedChange = onToggle,
    )
}
