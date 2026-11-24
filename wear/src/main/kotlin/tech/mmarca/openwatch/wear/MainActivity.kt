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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The watch app: today's dashboard, with the quick log, the workout recorder
 * and the settings one step down, the link and recorder status below them.
 * Asks for the permissions the link and the recorders need; each part
 * starts on its own once granted. The recording itself lives in
 * [WearAppService], so closing the app changes nothing.
 *
 * One instance (`singleTask`): a tile, the watch face pill or a notification
 * reaches the one already open. A tile may name the screen to open
 * ([EXTRA_ROUTE]), which `OpenWatchWearApp` opens once if a tile may open it
 * (`WearRoutes.isDeepLinkable`).
 */
class MainActivity : ComponentActivity() {

    private val viewModel: WearAppViewModel by viewModels()
    private val graph by lazy { wearGraph }

    /** Bumped when the watch face pill opens the app again: a running workout comes forward. */
    private var workoutRequests by mutableIntStateOf(0)

    /** The screen a tile asked for, until the navigation has opened it. */
    private var deepLink by mutableStateOf<String?>(null)

    /** What the screens ask of the activity. */
    private val actions = ActivityActions(
        requestGrants = ::requestMissingPermissions,
        openBluetoothSettings = ::openBluetoothSettings,
    )

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
        // A recreated activity has its screens back already; the extra was for its first start.
        if (savedInstanceState == null) deepLink = intent.getStringExtra(EXTRA_ROUTE)

        setContent {
            val uiState by viewModel.uiState.collectAsStateWithLifecycle()
            val workout by graph.workoutState.state.collectAsStateWithLifecycle()
            OpenWatchWearApp(
                state = uiState,
                workoutActive = workout.running || workout.finished,
                workoutRequests = workoutRequests,
                deepLink = deepLink,
                onDeepLinkHandled = { deepLink = null },
                onLog = viewModel::log,
                onEditTiles = viewModel::editTiles,
                onResetTiles = viewModel::resetTiles,
                spot = graph.spot::run,
                actions = actions,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        deepLink = intent.getStringExtra(EXTRA_ROUTE)
        workoutRequests++
    }

    override fun onResume() {
        super.onResume()
        // Also catches a grant made in the system settings; the status and settings screens read the grants again by themselves.
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

    companion object {
        /** The screen a tile opens the app at, a route; see `WearRoutes.isDeepLinkable`. */
        const val EXTRA_ROUTE = "tech.mmarca.openwatch.wear.ROUTE"
    }
}
