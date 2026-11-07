package tech.mmarca.openwatch.navigation

import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import tech.mmarca.openwatch.features.watches.WatchDeviceScreen

/** The watch-facing destinations. Plain pushed screens. */
internal fun NavGraphBuilder.watchRoutes(
    navController: NavHostController,
) {
    composable(Screen.WatchDevice.route) {
        WatchDeviceScreen(
            viewModel = hiltViewModel(),
            onRemoved = { navController.popBackStack() },
        )
    }
}
