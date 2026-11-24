package tech.mmarca.openwatch.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * Puts the app-wide state together: what the watch recorded today, with the
 * heart rate zones on the basis the settings give now, so a maximum or a
 * resting rate the phone sends shows without the app being opened again,
 * and the entries made on the watch, which count toward the day's figures
 * the moment they are logged. Only the metrics the watch can give are
 * offered ([MetricOffer]), and the dashboard shows those of the tiles the
 * wearer chose ([WearTilePreferences]), which the tile editor changes here,
 * as the quick log's editor changes its entries ([WearQuickLogPreferences]).
 * Every screen shows its readings in the unit system the settings hold now,
 * and the phone's figures for the last days ([PhoneDays]) fill the sleep,
 * resting heart rate, active calories and distance tiles as they arrive.
 */
class WearAppViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = application.wearGraph

    private val readings = WearReadingsSource(graph.store, waterGoalMl = DisplayPreferences.DEFAULT_WATER_GOAL_ML.toDouble())

    /** The settings as they change. The first read loads their file: collected off the main thread. */
    private val settings = flow { emitAll(graph.settings) }.flowOn(Dispatchers.IO)

    /** The days the phone put; the first read loads their file: collected off the main thread. */
    private val phoneDays = flow { emitAll(graph.phoneDays.days) }.flowOn(Dispatchers.IO)

    /** Read once, off the main thread: the sensor list and the package manager. */
    private val sensors = flow { emit(graph.sensors) }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<WearUiState> =
        combine(
            readings.metrics(zoneBasis = settings.map { it.zoneBasis() }, phoneDays = phoneDays),
            readings.entries(),
            sensors,
            // The first read of the chosen tiles and quick log entries loads their files too.
            flow { emitAll(combine(graph.tiles.chosen, graph.quickLog.chosen) { tiles, items -> tiles to items }) }.flowOn(Dispatchers.IO),
            settings.map { DisplayPreferences(unitSystem = it.unitSystem) }.distinctUntilChanged(),
        ) { readings, entries, sensors, (tiles, quickLogItems), preferences ->
            WearUiState(
                availableMetrics = MetricOffer.available(sensors, withReadings = readings.metrics.keys),
                sensors = sensors,
                metrics = readings.metrics,
                preferences = preferences,
                sleepStages = readings.sleepStages,
                entries = entries,
                tiles = tiles,
                quickLogItems = quickLogItems,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), WearUiState())

    /** Logs [value] (and [second]) of [type], entered by hand now. The write is off the main thread. */
    fun log(type: WearLinkEntry.Type, value: Double, second: Double? = null) {
        val at = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) { graph.entries.log(type, value, second, atMillis = at) }
    }

    /** Changes the dashboard's chosen tiles: [change] gets them as they are when it runs. The write is off the main thread. */
    fun editTiles(change: (List<MetricTile>) -> List<MetricTile>) {
        viewModelScope.launch(Dispatchers.IO) { graph.tiles.edit(change) }
    }

    /** The dashboard's default tiles again. */
    fun resetTiles() {
        viewModelScope.launch(Dispatchers.IO) { graph.tiles.reset() }
    }

    /** Changes the quick log's chosen entries: [change] gets them as they are when it runs. The write is off the main thread. */
    fun editQuickLog(change: (List<QuickLogItem>) -> List<QuickLogItem>) {
        viewModelScope.launch(Dispatchers.IO) { graph.quickLog.edit(change) }
    }

    /** Every quick log entry again, in the default order. */
    fun resetQuickLog() {
        viewModelScope.launch(Dispatchers.IO) { graph.quickLog.reset() }
    }

    private companion object {
        const val StopTimeoutMillis = 5_000L
    }
}
