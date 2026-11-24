package tech.mmarca.openwatch.wear.features.measure

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openwatch.wear.SpotEvent
import tech.mmarca.openwatch.wear.SpotFailure
import tech.mmarca.openwatch.wear.SpotKind
import tech.mmarca.openwatch.wear.haptics.HapticCue

/** The measurement screen's ViewModel: what it shows of the events a measurement sends. */
class MeasureViewModelTest {

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val asked = mutableListOf<SpotKind>()

    @After
    fun tearDown() = scope.cancel()

    private fun viewModel(kind: SpotKind = SpotKind.HEART_RATE, events: Flow<SpotEvent>) =
        MeasureViewModel(kind, { asked += it; events }, scope)

    @Test
    fun `it starts ready, and a reading ends in done`() {
        val viewModel = viewModel(events = flowOf(SpotEvent.Live(70.0), SpotEvent.Result(71.0)))
        assertEquals(MeasureUiState.Ready, viewModel.state.value)

        viewModel.start()

        assertEquals(MeasureUiState.Done(71.0, null), viewModel.state.value)
        assertEquals(listOf(SpotKind.HEART_RATE), asked)
    }

    @Test
    fun `while it runs the live value and the wait show, and a failure ends it`() {
        val seen = mutableListOf<MeasureUiState>()
        lateinit var measuring: MeasureViewModel
        measuring = viewModel(
            events = flow {
                // The collector has taken each event once its emit returns.
                emit(SpotEvent.Waiting(offBody = true))
                seen += measuring.state.value
                emit(SpotEvent.Live(64.0))
                seen += measuring.state.value
                emit(SpotEvent.Failed(SpotFailure.NO_SIGNAL))
            },
        )

        measuring.start()

        assertEquals(
            listOf(
                MeasureUiState.Running(progress = 0f, live = null, waiting = true, offBody = true),
                MeasureUiState.Running(progress = 0f, live = 64.0, waiting = false, offBody = false),
            ),
            seen,
        )
        assertEquals(MeasureUiState.Failed(SpotFailure.NO_SIGNAL), measuring.state.value)
    }

    @Test
    fun `a run that ends without its reading failed, and so does one that throws`() {
        val empty = viewModel(events = emptyFlow())
        empty.start()
        assertEquals(MeasureUiState.Failed(SpotFailure.FAILED), empty.state.value)

        val refused = viewModel(kind = SpotKind.HRV, events = flow { throw SecurityException("no grant") })
        refused.start()
        assertEquals(MeasureUiState.Failed(SpotFailure.PERMISSION_DENIED), refused.state.value)
    }

    @Test
    fun `a grant refused before the start is shown as such`() {
        val viewModel = viewModel(events = emptyFlow())

        viewModel.fail(SpotFailure.PERMISSION_DENIED)

        assertEquals(MeasureUiState.Failed(SpotFailure.PERMISSION_DENIED), viewModel.state.value)
        assertEquals(emptyList<SpotKind>(), asked)
    }

    @Test
    fun `a run that ends is told once, a reading and a failure alike, and the wrist buzzes for it`() {
        val ends = mutableListOf<MeasureUiState>()
        MeasureViewModel(SpotKind.HEART_RATE, { flowOf(SpotEvent.Result(71.0)) }, scope, onEnd = { ends += it }).start()
        MeasureViewModel(SpotKind.HRV, { flowOf(SpotEvent.Failed(SpotFailure.MOVED)) }, scope, onEnd = { ends += it }).start()

        assertEquals(listOf(MeasureUiState.Done(71.0, null), MeasureUiState.Failed(SpotFailure.MOVED)), ends)
        assertEquals(listOf(HapticCue.PULSE, HapticCue.DOUBLE_PULSE), ends.map { it.endCue() })
    }

    @Test
    fun `a failure found before anything ran, and a state that is no end, are silent`() {
        for (failure in listOf(SpotFailure.NOT_SUPPORTED, SpotFailure.PERMISSION_DENIED, SpotFailure.BUSY)) {
            assertNull(failure.name, MeasureUiState.Failed(failure).endCue())
        }
        for (failure in listOf(SpotFailure.OFF_BODY, SpotFailure.NO_SIGNAL, SpotFailure.FAILED)) {
            assertEquals(failure.name, HapticCue.DOUBLE_PULSE, MeasureUiState.Failed(failure).endCue())
        }
        assertNull(MeasureUiState.Ready.endCue())
        assertNull(MeasureUiState.Running(progress = 0.5f, live = 70.0, waiting = false, offBody = false).endCue())
    }
}
