package tech.mmarca.openwatch.wear.features.status

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.material3.lazy.TransformationSpec
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearPermissions
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/** The settings' buttons: each switches its setting between two values. */
internal class StatusSettingsActions(
    val onHeartRatePowerSave: (Boolean) -> Unit,
    val onSyncedSleepHold: (Boolean) -> Unit,
    val onHeartRateSource: (WearSettings.Source) -> Unit,
    val onHrvSource: (WearSettings.Source) -> Unit,
    /** Switches skin temperature on or off; on asks for the platform's grant first. */
    val onSkinTemperature: (Boolean) -> Unit,
    val onSpo2AtNight: (Boolean) -> Unit,
)

/**
 * The actions of [viewModel]'s settings. Switching skin temperature or SpO2
 * on asks for the permission the Samsung platform checks; without it the
 * setting stays off.
 */
@Composable
internal fun rememberStatusSettingsActions(viewModel: StatusViewModel): StatusSettingsActions {
    val context = LocalContext.current
    val askSkin = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setSkinTemperature(true)
    }
    val askSpo2 = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.setSpo2AtNight(true)
    }
    return remember(viewModel, askSkin, askSpo2) {
        StatusSettingsActions(
            onHeartRatePowerSave = viewModel::setHeartRatePowerSave,
            onSyncedSleepHold = viewModel::setSyncedSleepHold,
            onHeartRateSource = viewModel::setHeartRateSource,
            onHrvSource = viewModel::setHrvSource,
            onSkinTemperature = { on ->
                if (on && !WearPermissions.hasSkinTemperature(context)) askSkin.launch(WearPermissions.skinTemperature) else viewModel.setSkinTemperature(on)
            },
            onSpo2AtNight = { on ->
                if (on && !WearPermissions.hasOxygenSaturation(context)) askSpo2.launch(WearPermissions.oxygenSaturation) else viewModel.setSpo2AtNight(on)
            },
        )
    }
}

/**
 * The watch's own settings ([WearSettings]) on the status screen: the heart
 * rate outside bedtime mode and how a sleep hold works, and, on a watch with
 * the Samsung Health Platform only, where the heart rate, the HRV windows,
 * skin temperature and SpO2 come from. The running service follows a change
 * at once, and a change the phone makes shows at once too: [settings] is the
 * stored settings as they change.
 */
internal fun TransformingLazyColumnScope.settingsItems(
    settings: WatchPreferences,
    samsung: Boolean,
    spec: TransformationSpec,
    actions: StatusSettingsActions,
) {
    val powerSave = settings.heartRateDay == HeartRateInterval.POWER_SAVE
    statusButton(spec, if (powerSave) R.string.settings_heart_rate_power_save else R.string.settings_heart_rate_every_10_seconds) {
        actions.onHeartRatePowerSave(!powerSave)
    }
    if (powerSave) item { StatusLine(stringResource(R.string.settings_heart_rate_power_save_note)) }
    val synced = settings.syncedSleepHold
    statusButton(spec, if (synced) R.string.settings_sleep_hold_synced else R.string.settings_sleep_hold_wake_lock) {
        actions.onSyncedSleepHold(!synced)
    }
    if (!samsung) return

    val heartRateSamsung = settings.heartRateSource == WearSettings.Source.SAMSUNG_PLATFORM
    statusButton(spec, if (heartRateSamsung) R.string.settings_heart_rate_source_samsung else R.string.settings_heart_rate_source_sensor) {
        actions.onHeartRateSource(flip(settings.heartRateSource))
    }
    val hrvSamsung = settings.hrvSource == WearSettings.Source.SAMSUNG_PLATFORM
    statusButton(spec, if (hrvSamsung) R.string.status_hrv_source_samsung else R.string.status_hrv_source_ppg) {
        actions.onHrvSource(flip(settings.hrvSource))
    }
    val skin = settings.skinTemperature
    statusButton(spec, if (skin) R.string.settings_skin_temperature_on else R.string.settings_skin_temperature_off) {
        actions.onSkinTemperature(!skin)
    }
    val spo2 = settings.spo2AtNight
    statusButton(spec, if (spo2) R.string.settings_spo2_on else R.string.settings_spo2_off) {
        actions.onSpo2AtNight(!spo2)
    }
    if (heartRateSamsung || hrvSamsung || skin || spo2) item { StatusLine(stringResource(R.string.status_hrv_source_samsung_note)) }
}

private fun flip(source: WearSettings.Source): WearSettings.Source =
    if (source == WearSettings.Source.SAMSUNG_PLATFORM) WearSettings.Source.RAW_SENSOR else WearSettings.Source.SAMSUNG_PLATFORM
