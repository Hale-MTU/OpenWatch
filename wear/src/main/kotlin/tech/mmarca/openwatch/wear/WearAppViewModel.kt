package tech.mmarca.openwatch.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
 * wearer chose ([WearTilePreferences]), which the tile editor changes here.
 */
class WearAppViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = application.wearGraph

    private val preferences = DisplayPreferences()

    private val readings = WearReadingsSource(graph.store, waterGoalMl = preferences.waterGoalMl.toDouble())

    /** Read once, off the main thread: the sensor list and the package manager. */
    private val sensors = flow { emit(graph.sensors) }.flowOn(Dispatchers.IO)

    val uiState: StateFlow<WearUiState> =
        combine(
            // The first read of the settings loads their file: the source collects them where it reads, off the main thread.
            readings.metrics(zoneBasis = flow { emitAll(graph.settings) }.map { it.zoneBasis() }),
            readings.entries(),
            sensors,
            // The first read of the chosen tiles loads their file too.
            flow { emitAll(graph.tiles.chosen) }.flowOn(Dispatchers.IO),
        ) { metrics, entries, sensors, tiles ->
            WearUiState(
                availableMetrics = MetricOffer.available(sensors, withReadings = metrics.keys),
                sensors = sensors,
                metrics = metrics,
                preferences = preferences,
                entries = entries,
                tiles = tiles,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), WearUiState(preferences = preferences))

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

    private companion object {
        const val StopTimeoutMillis = 5_000L
    }
}
