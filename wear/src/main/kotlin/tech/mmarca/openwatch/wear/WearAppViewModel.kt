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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * Puts the app-wide state together: what the watch recorded today, with the
 * heart rate zones on the basis the settings give now, so a maximum or a
 * resting rate the phone sends shows without the app being opened again,
 * and the entries made on the watch, which count toward the day's figures
 * the moment they are logged.
 */
class WearAppViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = application.wearGraph

    private val preferences = DisplayPreferences()

    private val readings = WearReadingsSource(graph.store, waterGoalMl = preferences.waterGoalMl.toDouble())

    val uiState: StateFlow<WearUiState> =
        combine(
            // The first read of the settings loads their file: the source collects them where it reads, off the main thread.
            readings.metrics(zoneBasis = flow { emitAll(graph.settings) }.map { it.zoneBasis() }),
            readings.entries(),
        ) { metrics, entries -> WearUiState(metrics = metrics, preferences = preferences, entries = entries) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), WearUiState(preferences = preferences))

    /** Logs [value] (and [second]) of [type], entered by hand now. The write is off the main thread. */
    fun log(type: WearLinkEntry.Type, value: Double, second: Double? = null) {
        val at = System.currentTimeMillis()
        viewModelScope.launch(Dispatchers.IO) { graph.entries.log(type, value, second, atMillis = at) }
    }

    private companion object {
        const val StopTimeoutMillis = 5_000L
    }
}
