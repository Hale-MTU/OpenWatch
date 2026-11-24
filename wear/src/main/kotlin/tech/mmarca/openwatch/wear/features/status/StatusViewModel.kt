package tech.mmarca.openwatch.wear.features.status

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mmarca.openwatch.wear.PendingPhone
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearLinkState
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wear.wearGraph
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/**
 * The status screen's state: what the store holds and the grants, read off
 * the main thread every [REFRESH_MILLIS] while the screen is shown and at
 * once on [refresh]; the link's state as the service writes it; and the
 * settings as they change, on the watch or from the phone's `SET_PREF`.
 * Each change goes to the [StatusPort] off the main thread.
 */
internal class StatusViewModel(
    private val port: StatusPort,
    private val linkState: WearLinkState,
    settings: Flow<WatchPreferences>,
    /** One emission per periodic read; a test drives it by hand. */
    ticks: Flow<Unit>,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    scope: CoroutineScope,
) : ViewModel(scope) {

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** The last readings that came through, kept when a read fails. Written on the IO dispatcher. */
    @Volatile
    private var lastReadings: StatusReadings? = null

    private val readings: Flow<StatusReadings?> = merge(ticks, refreshes)
        .map {
            withContext(io) {
                // A phone's request the wearer left unanswered goes once it has expired.
                linkState.expirePending(clock())
                port.read()?.also { lastReadings = it } ?: lastReadings
            }
        }
        .onStart { emit(lastReadings) }

    private val settingsOrNull: Flow<WatchPreferences?> = settings.flowOn(io)

    val state: StateFlow<StatusUiState> =
        combine(readings, linkState.state, settingsOrNull.onStart { emit(null) }) { readings, link, prefs ->
            StatusUiState(readings = readings, link = link, settings = prefs)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StatusUiState(link = linkState.state.value))

    /** Reads again now: the screen came back, or a grant may have changed. */
    fun refresh() {
        refreshes.tryEmit(Unit)
    }

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

    fun allow(phone: PendingPhone) {
        viewModelScope.launch(io) { port.allow(phone) }
    }

    fun block(phone: PendingPhone) {
        viewModelScope.launch(io) { port.block(phone) }
    }

    fun togglePpgLog() = port.togglePpgLog()

    private fun change(edit: (WatchPreferences) -> WatchPreferences) {
        viewModelScope.launch(io) { port.changeSettings(edit) }
    }

    companion object {
        /** How often the counts are read while the screen is shown. */
        const val REFRESH_MILLIS = 3_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** One tick at once, then one every [periodMillis]. */
        fun every(periodMillis: Long): Flow<Unit> = flow {
            while (true) {
                emit(Unit)
                delay(periodMillis)
            }
        }

        /** The screen's ViewModel over the process's graph. */
        fun create(context: Context): StatusViewModel {
            val app = context.applicationContext
            val graph = app.wearGraph
            return StatusViewModel(
                port = GraphStatusPort(app, graph),
                linkState = graph.linkState,
                // The first read of the settings loads their file: on the IO dispatcher, like every read here.
                settings = flow { emitAll(graph.settings) },
                ticks = every(REFRESH_MILLIS),
                io = Dispatchers.IO,
                clock = System::currentTimeMillis,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }
    }
}
