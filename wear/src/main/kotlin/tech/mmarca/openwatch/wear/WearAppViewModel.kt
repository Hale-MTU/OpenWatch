package tech.mmarca.openwatch.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Puts the app-wide state together: what the watch recorded today. */
class WearAppViewModel(application: Application) : AndroidViewModel(application) {

    private val readings = WearReadingsSource(MetricStore(application))

    private val initial = WearUiState()

    val uiState: StateFlow<WearUiState> =
        readings.metrics()
            .map { metrics -> initial.copy(metrics = metrics) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), initial)

    private companion object {
        const val StopTimeoutMillis = 5_000L
    }
}
