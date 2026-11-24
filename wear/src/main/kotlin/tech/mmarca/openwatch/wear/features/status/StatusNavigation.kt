package tech.mmarca.openwatch.wear.features.status

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.wear.compose.navigation.composable
import tech.mmarca.openwatch.wear.WearRoutes

/**
 * The link and recorder status, `status`. Its [content] comes from the
 * activity, which holds the permission requests and the system settings it
 * opens.
 */
internal fun NavGraphBuilder.statusDestination(content: @Composable () -> Unit) {
    composable(WearRoutes.STATUS) { content() }
}
