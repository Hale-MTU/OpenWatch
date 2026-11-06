package tech.mmarca.openwatch.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.PermissionController
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway

class HealthConnectPermissionLauncherState internal constructor(
    internal val launch: (Set<String>) -> Unit,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface HealthConnectGatewayEntryPoint {
    fun gateway(): HealthConnectGateway
}

/** The Health Connect permission dialog; the granted set is re-read once it closes. */
@Composable
fun rememberHealthConnectPermissionLauncher(
    onResult: () -> Unit = {},
    /** After [onResult], only when the user granted at least one permission. */
    onGranted: () -> Unit = {},
): HealthConnectPermissionLauncherState {
    val appContext = LocalContext.current.applicationContext
    val launcher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        EntryPointAccessors.fromApplication(appContext, HealthConnectGatewayEntryPoint::class.java)
            .gateway()
            .invalidateGrantedPermissions()
        onResult()
        if (granted.isNotEmpty()) onGranted()
    }
    return HealthConnectPermissionLauncherState(
        launch = { permissions ->
            if (permissions.isEmpty()) return@HealthConnectPermissionLauncherState
            launcher.launch(permissions)
        },
    )
}
