package tech.mmarca.openwatch.wear.features.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.TransformationSpec
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.TrustedPhone
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.WearLinkUiState
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/**
 * The watch's settings, one row each, with how it stands: the phone and its
 * link, the sensor access (with the recording details one step further), and
 * the unit system, which a tap switches. A line still being read shows no
 * second label.
 */
@Composable
internal fun SettingsScreen(
    state: SettingsUiState,
    onPhoneClick: () -> Unit,
    onSensorAccessClick: () -> Unit,
    onUnitSystemChange: (UnitSystem) -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.settings_title)) }
            }
            settingsRow(
                spec = spec,
                label = R.string.settings_phone,
                value = if (state.phoneConnected) R.string.settings_phone_connected else R.string.settings_phone_not_connected,
                icon = Icons.Outlined.Smartphone,
                onClick = onPhoneClick,
            )
            settingsRow(
                spec = spec,
                label = R.string.settings_sensor_access,
                value = when (state.sensorAccessGranted) {
                    null -> null
                    true -> R.string.settings_sensor_access_granted
                    false -> R.string.settings_sensor_access_missing
                },
                icon = Icons.Outlined.Sensors,
                onClick = onSensorAccessClick,
            )
            val unitSystem = state.unitSystem
            settingsRow(
                spec = spec,
                label = R.string.settings_units,
                value = when (unitSystem) {
                    null -> null
                    UnitSystem.METRIC -> R.string.settings_units_metric
                    UnitSystem.IMPERIAL -> R.string.settings_units_imperial
                },
                icon = Icons.Outlined.Straighten,
                onClick = {
                    if (unitSystem != null) {
                        onUnitSystemChange(if (unitSystem == UnitSystem.METRIC) UnitSystem.IMPERIAL else UnitSystem.METRIC)
                    }
                },
            )
        }
    }
}

/** One row of the settings: what it is, how it stands, and a tap. */
private fun TransformingLazyColumnScope.settingsRow(
    spec: TransformationSpec,
    @StringRes label: Int,
    @StringRes value: Int?,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    item {
        Button(
            onClick = onClick,
            label = { Text(stringResource(label)) },
            secondaryLabel = value?.let { { Text(stringResource(it)) } },
            icon = { Icon(icon, contentDescription = null) },
            colors = ButtonDefaults.filledTonalButtonColors(),
            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
            transformation = SurfaceTransformation(spec),
        )
    }
}

@WearPreviews
@Composable
private fun SettingsScreenPreview() {
    val phone = TrustedPhone(address = "AA:BB", name = "Pixel 7", trustedAtMillis = 0L)
    OpenWatchWearTheme {
        AppScaffold {
            SettingsScreen(
                state = SettingsUiState(
                    link = WearLinkUiState(listening = true, trusted = listOf(phone)),
                    access = SensorAccess(heartRate = true, heartRateInBackground = true, steps = true, nearbyDevices = true),
                    unitSystem = UnitSystem.METRIC,
                ),
                onPhoneClick = {},
                onSensorAccessClick = {},
                onUnitSystemChange = {},
            )
        }
    }
}
