package tech.mmarca.openwatch.wear.features.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearAppService
import tech.mmarca.openwatch.wear.WearGraph
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wear.wearGraph
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/** What the recording settings read and change, apart from the screen: the watch's settings file ([GraphRecordingSettingsPort]), a fake in a test. */
internal interface RecordingSettingsPort {
    /** Whether the watch has the Samsung Health Platform, which adds its settings. Blocks. */
    fun samsung(): Boolean

    /** Whether the watch has any skin-temperature source: the Samsung platform or Pixel's raw sensor. Blocks. */
    fun skin(): Boolean

    /** Applies [change] to the stored settings and tells the running service. Blocks. */
    fun change(change: (WatchPreferences) -> WatchPreferences)
}

/** The recording settings screen's state; null parts are not read yet. */
internal data class RecordingSettingsUiState(
    val settings: WatchPreferences? = null,
    val samsung: Boolean = false,
    val skin: Boolean = false,
)

/**
 * The recording settings' state: the settings as they change, on the watch
 * or from the phone's `SET_PREF`, and whether the watch has the Samsung
 * platform, both read off the main thread; each change goes to the [port]
 * off the main thread too.
 */
internal class RecordingSettingsViewModel(
    private val port: RecordingSettingsPort,
    settings: Flow<WatchPreferences>,
    private val io: CoroutineDispatcher,
    scope: CoroutineScope,
) : ViewModel(scope) {

    private val samsung: Flow<Boolean> = flow { emit(port.samsung()) }.flowOn(io)
    private val skin: Flow<Boolean> = flow { emit(port.skin()) }.flowOn(io)

    val state: StateFlow<RecordingSettingsUiState> =
        combine(settings.flowOn(io), samsung.onStart { emit(false) }, skin.onStart { emit(false) }) { prefs, samsung, skin ->
            RecordingSettingsUiState(prefs, samsung, skin)
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), RecordingSettingsUiState())

    fun setHeartRatePowerSave(on: Boolean) = change {
        it.copy(heartRateDay = if (on) HeartRateInterval.POWER_SAVE else HeartRateInterval.EVERY_10_SECONDS)
    }

    fun setSyncedSleepHold(synced: Boolean) = change { it.copy(syncedSleepHold = synced) }

    fun setHeartRateSource(source: WearSettings.Source) = change { it.copy(heartRateSource = source) }

    fun setHrvSource(source: WearSettings.Source) = change { it.copy(hrvSource = source) }

    /** Skin temperature at night; switching on needs the grant the screen asked for. */
    fun setSkinTemperature(on: Boolean) = change { it.copy(skinTemperature = on) }

    /** SpO2 at night; switching on needs the grant the screen asked for. */
    fun setSpo2AtNight(on: Boolean) = change { it.copy(spo2AtNight = on) }

    /** Whether a run or a ride with a route pauses by itself; a workout that runs keeps what it started with. */
    fun setAutoPause(on: Boolean) = change { it.copy(autoPause = on) }

    /** Whether the watch offers to record a walk or a run it sees in the step minutes. */
    fun setAutoDetect(on: Boolean) = change { it.copy(autoDetect = on) }

    private fun change(edit: (WatchPreferences) -> WatchPreferences) {
        viewModelScope.launch(io) { port.change(edit) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** The screen's ViewModel over the process's graph. */
        fun create(context: Context): RecordingSettingsViewModel {
            val app = context.applicationContext
            val graph = app.wearGraph
            return RecordingSettingsViewModel(
                port = GraphRecordingSettingsPort(app, graph),
                // The first read of the settings loads their file: on the IO dispatcher.
                settings = flow { emitAll(graph.settings) },
                io = Dispatchers.IO,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }
    }
}

/** [RecordingSettingsPort] over the process's [WearGraph] and the settings file. */
internal class GraphRecordingSettingsPort(private val context: Context, private val graph: WearGraph) : RecordingSettingsPort {

    override fun samsung(): Boolean = graph.abilities().samsung

    override fun skin(): Boolean = graph.abilities().let { it.samsung || it.skinSensor }

    override fun change(change: (WatchPreferences) -> WatchPreferences) {
        WearSettings.write(context, change(WearSettings.read(context)))
        WearAppService.settingsChanged(context)
    }
}
