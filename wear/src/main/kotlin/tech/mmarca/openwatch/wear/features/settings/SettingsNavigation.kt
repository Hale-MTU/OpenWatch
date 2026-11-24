package tech.mmarca.openwatch.wear.features.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.composable
import tech.mmarca.openwatch.wear.ActivityActions
import tech.mmarca.openwatch.wear.WearRoutes

/**
 * The watch's settings, `settings`, which the dashboard opens, and the
 * screens they lead to: the phone, `phone`, the sensor access, `sensors`,
 * which leads on to the link and recorder status, and the recording
 * settings, `recording_settings`. What only the activity can do (ask for the
 * grants, open the Bluetooth settings) comes in [actions].
 */
internal fun NavGraphBuilder.settingsDestinations(navController: NavHostController, actions: ActivityActions) {
    composable(WearRoutes.SETTINGS) {
        val viewModel = settingsViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SettingsScreen(
            state = state,
            onPhoneClick = { navController.navigate(WearRoutes.PHONE) { launchSingleTop = true } },
            onSensorAccessClick = { navController.navigate(WearRoutes.SENSORS) { launchSingleTop = true } },
            onRecordingClick = { navController.navigate(WearRoutes.RECORDING_SETTINGS) { launchSingleTop = true } },
            onUnitSystemChange = viewModel::setUnitSystem,
        )
    }
    composable(WearRoutes.RECORDING_SETTINGS) { RecordingSettingsRoute() }
    composable(WearRoutes.PHONE) {
        val viewModel = settingsViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        PhoneScreen(
            state = state,
            onAllow = viewModel::allow,
            onBlock = viewModel::block,
            onOpenBluetoothSettings = actions.openBluetoothSettings,
        )
    }
    composable(WearRoutes.SENSORS) {
        val viewModel = settingsViewModel()
        val state by viewModel.state.collectAsStateWithLifecycle()
        SensorAccessScreen(
            access = state.access,
            onGrant = actions.requestGrants,
            onOpenDetails = { navController.navigate(WearRoutes.STATUS) { launchSingleTop = true } },
        )
    }
}

/** The destination's [SettingsViewModel], which reads the grants again each time the screen comes back. */
@Composable
private fun settingsViewModel(): SettingsViewModel {
    val context = LocalContext.current
    val viewModel = viewModel { SettingsViewModel.create(context) }
    LifecycleResumeEffect(viewModel) {
        viewModel.refresh()
        onPauseOrDispose {}
    }
    return viewModel
}
