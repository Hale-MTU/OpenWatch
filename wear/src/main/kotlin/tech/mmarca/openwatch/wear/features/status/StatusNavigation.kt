package tech.mmarca.openwatch.wear.features.status

import androidx.navigation.NavGraphBuilder
import androidx.wear.compose.navigation.composable
import tech.mmarca.openwatch.wear.ActivityActions
import tech.mmarca.openwatch.wear.WearRoutes

/**
 * The link and recorder status, `status`: the recording details the sensor
 * access screen opens. What only the activity can do, the grant requests and
 * the Bluetooth settings, comes in [actions].
 */
internal fun NavGraphBuilder.statusDestination(actions: ActivityActions, onOpenWorkout: () -> Unit) {
    composable(WearRoutes.STATUS) {
        StatusScreen(
            onGrant = actions.requestGrants,
            onOpenWorkout = onOpenWorkout,
            onOpenBluetoothSettings = actions.openBluetoothSettings,
        )
    }
}
