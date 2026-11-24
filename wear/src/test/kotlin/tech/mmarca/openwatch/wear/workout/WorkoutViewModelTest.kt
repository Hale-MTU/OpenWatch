package tech.mmarca.openwatch.wear.workout

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.openwatch.wearlink.WorkoutSport

/** The workout screen's ViewModel: what it sends the recorder, and when it sends nothing. */
class WorkoutViewModelTest {

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val workout = WorkoutState()
    private val control = FakeControl()
    private val viewModel = WorkoutViewModel(workout, control, HeartRateZones.Basis(maxBpm = 190), scope)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `a sport with a route asks for location only while the grant is missing`() {
        val gps = WorkoutSport.entries.first { it.gps }
        val indoor = WorkoutSport.entries.first { !it.gps }

        assertTrue(viewModel.needsLocation(gps))
        assertFalse(viewModel.needsLocation(indoor))
        control.location = true
        assertFalse(viewModel.needsLocation(gps))
    }

    @Test
    fun `a refused location starts the sport without a route, and a sport without one never asks the GPS`() {
        val gps = WorkoutSport.entries.first { it.gps }
        val indoor = WorkoutSport.entries.first { !it.gps }

        viewModel.start(gps, gps = false)
        viewModel.start(indoor, gps = true)
        viewModel.start(gps)

        assertEquals(listOf("start ${gps.id} false", "start ${indoor.id} false", "start ${gps.id} true"), control.calls)
    }

    @Test
    fun `the pause button pauses a running workout and resumes a paused one`() {
        running(paused = false)
        viewModel.togglePause()
        running(paused = true)
        viewModel.togglePause()

        assertEquals(listOf("pause", "resume"), control.calls)
    }

    @Test
    fun `nothing is sent for a workout that is not running`() {
        workout.update { WorkoutUiState(sport = WorkoutSport.WALKING, running = false, snapshot = snapshot(paused = false), finished = true) }

        viewModel.togglePause()
        viewModel.stop()

        assertEquals(emptyList<String>(), control.calls)
    }

    @Test
    fun `stop goes to the recorder, and closing the summary clears the finished workout`() {
        running(paused = false)
        viewModel.stop()
        workout.update { it.copy(running = false, finished = true) }

        viewModel.closeSummary()

        assertEquals(listOf("stop"), control.calls)
        assertEquals(WorkoutUiState(), viewModel.state.value)
    }

    @Test
    fun `the screen sees each snapshot the recorder publishes`() {
        running(paused = false)
        workout.update { it.copy(snapshot = snapshot(paused = false, seconds = 91)) }

        assertEquals(91L, viewModel.state.value.snapshot?.activeSeconds)
    }

    private fun running(paused: Boolean) {
        workout.update { WorkoutUiState(sport = WorkoutSport.WALKING, running = true, snapshot = snapshot(paused)) }
    }

    private fun snapshot(paused: Boolean, seconds: Long = 90) = WorkoutSnapshot(
        activeSeconds = seconds, paused = paused, distanceMetres = null, speedMetresPerSecond = null,
        averageSpeedMetresPerSecond = null, steps = null, cadenceStepsPerMinute = null, ascentMetres = null,
        floors = null, heartRateBpm = null, averageHeartRateBpm = null, maxHeartRateBpm = null, waitingForFix = false,
    )

    private class FakeControl : WorkoutControl {
        var location = false
        val calls = mutableListOf<String>()

        override fun hasLocation(): Boolean = location

        override fun start(sport: WorkoutSport, gps: Boolean) {
            calls += "start ${sport.id} $gps"
        }

        override fun pause() {
            calls += "pause"
        }

        override fun resume() {
            calls += "resume"
        }

        override fun stop() {
            calls += "stop"
        }
    }
}
