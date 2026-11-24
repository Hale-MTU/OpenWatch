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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mmarca.openwatch.wear.PendingPhone
import tech.mmarca.openwatch.wear.WearLinkState
import tech.mmarca.openwatch.wear.wearGraph

/**
 * The status screen's state: what the store holds and the grants, read off
 * the main thread every [REFRESH_MILLIS] while the screen is shown and at
 * once on [refresh], and the link's state as the service writes it. Each
 * change goes to the [StatusPort] off the main thread.
 */
internal class StatusViewModel(
    private val port: StatusPort,
    private val linkState: WearLinkState,
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

    val state: StateFlow<StatusUiState> =
        combine(readings, linkState.state) { readings, link -> StatusUiState(readings = readings, link = link) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), StatusUiState(link = linkState.state.value))

    /** Reads again now: the screen came back, or a grant may have changed. */
    fun refresh() {
        refreshes.tryEmit(Unit)
    }

    fun allow(phone: PendingPhone) {
        viewModelScope.launch(io) { port.allow(phone) }
    }

    fun block(phone: PendingPhone) {
        viewModelScope.launch(io) { port.block(phone) }
    }

    fun togglePpgLog() = port.togglePpgLog()

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
                ticks = every(REFRESH_MILLIS),
                io = Dispatchers.IO,
                clock = System::currentTimeMillis,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }
    }
}
