package tech.mmarca.openwatch.wear

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tech.mmarca.openwatch.wear.features.quicklog.EntryLog
import tech.mmarca.openwatch.wear.features.quicklog.LoggedEntry
import tech.mmarca.openwatch.wear.features.quicklog.withEntries

/**
 * Puts the app-wide state together: what the watch recorded and the entries
 * made on it.
 */
class WearAppViewModel(application: Application) : AndroidViewModel(application) {

    private val entryLog = EntryLog(application)
    private val readings = WearReadingsSource(MetricStore(application))

    private val initial = WearUiState()

    val uiState: StateFlow<WearUiState> =
        combine(entryLog.entries, readings.metrics()) { entries, metrics ->
            initial.copy(metrics = metrics).withEntries(entries)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(StopTimeoutMillis), initial)

    fun log(entry: LoggedEntry) = entryLog.add(entry)

    private companion object {
        const val StopTimeoutMillis = 5_000L
    }
}
