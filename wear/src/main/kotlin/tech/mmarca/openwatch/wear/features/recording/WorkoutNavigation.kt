package tech.mmarca.openwatch.wear.features.recording

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.composable
import tech.mmarca.openwatch.wear.WearRoutes
import tech.mmarca.openwatch.wear.WearUiState

/**
 * The workout, `workout`: the sport picker, the running workout and the
 * summary of a saved one ([WorkoutScreen]), in the unit system of [state].
 * It closes itself when the wearer leaves the picker or the summary.
 */
internal fun NavGraphBuilder.workoutDestination(navController: NavHostController, state: () -> WearUiState) {
    composable(WearRoutes.WORKOUT) {
        WorkoutScreen(state().preferences.unitSystem) { navController.popBackStack() }
    }
}
