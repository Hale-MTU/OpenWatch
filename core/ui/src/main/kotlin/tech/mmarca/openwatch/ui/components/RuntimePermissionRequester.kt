package tech.mmarca.openwatch.ui.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** Asks Android for runtime permissions in one batched dialog. Made by [rememberRuntimePermissionRequester]. */
class RuntimePermissionRequester internal constructor(
    private val launcher: ActivityResultLauncher<Array<String>>,
) {
    /** Shows the dialog for [permissions]. Nothing to ask, nothing shown, and no result comes back. */
    fun request(permissions: Collection<String>) {
        if (permissions.isEmpty()) return
        launcher.launch(permissions.toTypedArray())
    }
}

/**
 * The one way a screen asks for runtime grants (Bluetooth, location, calendar, another app's
 * permission). [onResult] gets each permission asked with whether it is now granted; a request
 * the system cancelled comes back empty. Health Connect grants go through
 * [rememberHealthConnectPermissionLauncher] instead.
 */
@Composable
fun rememberRuntimePermissionRequester(
    onResult: (granted: Map<String, Boolean>) -> Unit,
): RuntimePermissionRequester {
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = onResult,
    )
    return remember(launcher) { RuntimePermissionRequester(launcher) }
}

/** Whether every permission asked was granted. An empty answer is a cancelled request, not a grant. */
fun Map<String, Boolean>.allGranted(): Boolean = isNotEmpty() && values.all { it }
