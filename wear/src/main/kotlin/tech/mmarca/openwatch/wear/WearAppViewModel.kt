package tech.mmarca.openwatch.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Puts the app-wide state together: what the watch recorded today, with the
 * heart rate zones on the basis the settings give now, so a maximum or a
 * resting rate the phone sends shows without the app being opened again.
 */
class WearAppViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = application.wearGraph

    private val readings = WearReadingsSource(graph.store)

    val uiState: StateFlow<WearUiState> =
        // The first read of the settings loads their file: the source collects them where it reads, off the main thread.
        readings.metrics(zoneBasis = flow { emitAll(graph.settings) }.map { it.zoneBasis() })
            .map { metrics -> WearUiState(metrics = metrics) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), WearUiState())

    private companion object {
        const val StopTimeoutMillis = 5_000L
    }
}
