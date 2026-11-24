package tech.mmarca.openwatch.wear.features.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.mmarca.openwatch.wear.PendingPhone
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearLinkState
import tech.mmarca.openwatch.wear.wearGraph

/**
 * The settings screens' state: the link as the service writes it, the
 * grants, read off the main thread when a screen starts and again on
 * [refresh] (a screen comes back, as after the grant dialog or the system
 * settings), and the unit system as the settings hold it. Each change goes to
 * the [SettingsPort] off the main thread.
 */
internal class SettingsViewModel(
    private val port: SettingsPort,
    private val linkState: WearLinkState,
    settings: Flow<WatchPreferences>,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    scope: CoroutineScope,
) : ViewModel(scope) {

    private val refreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val access: Flow<SensorAccess?> = merge(flowOf(Unit), refreshes)
        .map<Unit, SensorAccess?> {
            withContext(io) {
                // A phone's request the wearer left unanswered goes once it has expired.
                linkState.expirePending(clock())
                port.access()
            }
        }
        .onStart { emit(null) }

    private val unitSystem: Flow<UnitSystem?> = settings.map<WatchPreferences, UnitSystem?> { it.unitSystem }.flowOn(io).onStart { emit(null) }

    val state: StateFlow<SettingsUiState> =
        combine(linkState.state, access, unitSystem) { link, access, unitSystem ->
            SettingsUiState(link = link, access = access, unitSystem = unitSystem)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS), SettingsUiState(link = linkState.state.value))

    /** Reads the grants again: the screen came back. */
    fun refresh() {
        refreshes.tryEmit(Unit)
    }

    fun allow(phone: PendingPhone) {
        viewModelScope.launch(io) { port.allow(phone) }
    }

    fun block(phone: PendingPhone) {
        viewModelScope.launch(io) { port.block(phone) }
    }

    fun setUnitSystem(unitSystem: UnitSystem) {
        viewModelScope.launch(io) { port.setUnitSystem(unitSystem) }
    }

    companion object {
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        /** The screens' ViewModel over the process's graph. */
        fun create(context: Context): SettingsViewModel {
            val app = context.applicationContext
            val graph = app.wearGraph
            return SettingsViewModel(
                port = GraphSettingsPort(app, graph),
                linkState = graph.linkState,
                // The first read of the settings loads their file: on the IO dispatcher, like every read here.
                settings = flow { emitAll(graph.settings) },
                io = Dispatchers.IO,
                clock = System::currentTimeMillis,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            )
        }
    }
}
