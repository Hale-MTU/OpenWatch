package tech.mmarca.openwatch.wear.features.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.ui.preview.WearPreviews
import tech.mmarca.openwatch.wear.ui.theme.OpenWatchWearTheme

/**
 * What the watch app may use, one row per grant, a way to ask for the ones
 * that are missing ([onGrant]), and the recording details one step further
 * ([onOpenDetails]): the status screen, with what the recorders hold and
 * their settings.
 */
@Composable
internal fun SensorAccessScreen(
    access: SensorAccess?,
    onGrant: () -> Unit,
    onOpenDetails: () -> Unit,
) {
    val listState = rememberTransformingLazyColumnState()
    val spec = rememberTransformationSpec()

    ScreenScaffold(scrollState = listState) { contentPadding ->
        TransformingLazyColumn(state = listState, contentPadding = contentPadding) {
            item {
                ListHeader(
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                ) { Text(stringResource(R.string.settings_sensor_access)) }
            }
            if (access == null) {
                item { CircularProgressIndicator() }
            } else {
                grantRow(R.string.sensors_heart_rate, access.heartRate)
                grantRow(R.string.sensors_heart_rate_background, access.heartRateInBackground)
                item {
                    InfoRow(
                        label = stringResource(R.string.sensors_steps),
                        value = when (access.steps) {
                            null -> stringResource(R.string.sensors_no_step_counter)
                            true -> stringResource(R.string.settings_sensor_access_granted)
                            false -> stringResource(R.string.settings_sensor_access_missing)
                        },
                    )
                }
                grantRow(R.string.sensors_nearby_devices, access.nearbyDevices)
                if (!access.complete) {
                    item {
                        Button(
                            onClick = onGrant,
                            label = { Text(stringResource(R.string.action_grant)) },
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                            transformation = SurfaceTransformation(spec),
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = onOpenDetails,
                    label = { Text(stringResource(R.string.sensors_recording_details)) },
                    colors = ButtonDefaults.filledTonalButtonColors(),
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, spec),
                    transformation = SurfaceTransformation(spec),
                )
            }
        }
    }
}

private fun TransformingLazyColumnScope.grantRow(@StringRes label: Int, granted: Boolean) {
    item {
        InfoRow(
            label = stringResource(label),
            value = stringResource(if (granted) R.string.settings_sensor_access_granted else R.string.settings_sensor_access_missing),
        )
    }
}

@WearPreviews
@Composable
private fun SensorAccessScreenPreview() {
    OpenWatchWearTheme {
        AppScaffold {
            SensorAccessScreen(
                access = SensorAccess(heartRate = true, heartRateInBackground = false, steps = true, nearbyDevices = true),
                onGrant = {},
                onOpenDetails = {},
            )
        }
    }
}
