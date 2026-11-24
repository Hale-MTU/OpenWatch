package tech.mmarca.openwatch.wear

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.mmarca.openwatch.wear.features.status.StatusScreen
import tech.mmarca.openwatch.wear.features.recording.WorkoutScreen

/**
 * The watch app: today's dashboard, with the workout recorder and the link
 * and recorder status one step down. Asks for the permissions
 * the link and the recorders need; each part starts on its own once
 * granted. The recording itself lives in [WearAppService], so closing the
 * app changes nothing.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: WearAppViewModel by viewModels()
    private val graph by lazy { wearGraph }

    /** Bumped when the watch face pill opens the app again: a running workout comes forward. */
    private var workoutRequests by mutableIntStateOf(0)

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        WearAppService.startIfPermitted(this)
        // The background grant is asked on its own, and only after the foreground one.
        requestBackgroundHeartRateIfNeeded()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestMissingPermissions()

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val workout by graph.workoutState.state.collectAsStateWithLifecycle()
            OpenWatchWearApp(
                state = uiState,
                workoutActive = workout.running || workout.finished,
                workoutRequests = workoutRequests,
                statusScreen = { onOpenWorkout ->
                    StatusScreen(
                        onGrant = ::requestMissingPermissions,
                        onOpenWorkout = onOpenWorkout,
                        onOpenBluetoothSettings = ::openBluetoothSettings,
                    )
                },
                workoutScreen = { onClose -> WorkoutScreen(uiState.preferences.unitSystem, onClose) },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        workoutRequests++
    }

    override fun onResume() {
        super.onResume()
        // Also catches a grant made in the system settings; the status screen reads the grants again by itself.
        WearAppService.startIfPermitted(this)
    }

    private fun openBluetoothSettings() {
        // Not every watch has a Bluetooth settings activity; the system settings are the fallback.
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
    }

    private fun requestMissingPermissions() {
        val wanted = buildList {
            add(WearPermissions.heartRate)
            add(android.Manifest.permission.ACTIVITY_RECOGNITION)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                add(android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            // The link's ongoing notification and the phone requests; the service runs without it, just unseen.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            requestBackgroundHeartRateIfNeeded()
        } else {
            requestPermissions.launch(missing.toTypedArray())
        }
    }

    private fun requestBackgroundHeartRateIfNeeded() {
        val background = WearPermissions.heartRateInBackground ?: return
        if (!WearPermissions.hasHeartRate(this) || WearPermissions.hasHeartRateInBackground(this)) return
        requestPermissions.launch(arrayOf(background))
    }
}
