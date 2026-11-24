package tech.mmarca.openwatch.wear.workout

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WatchSensors
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wearlink.HeartRateInterval
import tech.mmarca.openwatch.wearlink.WorkoutMetrics
import tech.mmarca.openwatch.wearlink.WorkoutSport

/** The workout screen's ViewModel: what it sends the recorder, and when it sends nothing. */
class WorkoutViewModelTest {

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val workout = WorkoutState()
    private val control = FakeControl()
    private val settings = MutableStateFlow(
        WatchPreferences(
            heartRateDay = HeartRateInterval.EVERY_10_SECONDS,
            heartRateSource = WearSettings.Source.RAW_SENSOR,
            hrvSource = WearSettings.Source.RAW_SENSOR,
            syncedSleepHold = true,
            skinTemperature = false,
            spo2AtNight = false,
        ),
    )
    private val viewModel = WorkoutViewModel(workout, control, settings, scope)

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun `the zones rest on the settings, and follow a maximum and a resting rate the phone sends`() {
        assertNull("no zones before the phone sends a maximum", viewModel.zoneBasis.value)

        settings.value = settings.value.copy(maxHeartRate = 176, restingHeartRate = 52)

        assertEquals(HeartRateZones.Basis(maxBpm = 176, restingBpm = 52), viewModel.zoneBasis.value)
    }

    @Test
    fun `until the settings are read there is no basis to draw a zone on`() {
        assertNull(WorkoutViewModel(workout, control, emptyFlow(), scope).zoneBasis.value)
    }

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
    fun `a watch without a GPS receiver never asks for location and records no route`() {
        val gps = WorkoutSport.entries.first { it.gps }
        val bare = WorkoutViewModel(workout, control, settings, scope, flowOf(WatchSensors.ALL.copy(gps = false)))

        assertFalse(bare.needsLocation(gps))
        bare.start(gps)

        assertEquals(listOf("start ${gps.id} false"), control.calls)
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

        assertEquals(listOf("stop NONE"), control.calls)
        assertEquals(WorkoutUiState(), viewModel.state.value)
    }

    @Test
    fun `a stop that measures the recovery says so, and a saved workout's figures are read by its start`() {
        val read = mutableListOf<Long>()
        val summary = WorkoutMetrics.Summary(140, 171, List(5) { 60L }, 42.0, 310.0, 380.0, emptyList(), null, null)
        val figured = WorkoutViewModel(workout, control, settings, scope, figuresOf = { start -> read += start; summary }, io = Dispatchers.Unconfined)
        running(paused = false)
        workout.update { it.copy(startMillis = 1_794_000_000_000L) }

        figured.stop(RecoveryAtStop.LIVE)
        assertNull("nothing is read while the workout runs", figured.figures.value)
        workout.update { it.copy(running = false, finished = true) }

        assertEquals(listOf("stop LIVE"), control.calls)
        assertEquals(summary, figured.figures.value)
        assertEquals(listOf(1_794_000_000_000L), read)
    }

    @Test
    fun `a stop asks about the recovery only with zones and a heart rate in them, and reads a rest before it`() {
        workout.update { WorkoutUiState(sport = WorkoutSport.RUNNING, running = true, snapshot = snapshot(paused = false).copy(heartRateBpm = 150)) }
        assertEquals("no zones from the phone yet", RecoveryAtStop.NONE, viewModel.recoveryAtStop())

        settings.value = settings.value.copy(maxHeartRate = 190, restingHeartRate = 60)
        assertEquals(RecoveryAtStop.LIVE, viewModel.recoveryAtStop())

        val rest = RestRecovery(startMillis = 0, peakBpm = 165, oneMinuteBpm = 28, twoMinutesBpm = null)
        workout.update { it.copy(snapshot = it.snapshot?.copy(rest = rest)) }
        assertEquals(RecoveryAtStop.FROM_REST, viewModel.recoveryAtStop())

        workout.update { it.copy(snapshot = it.snapshot?.copy(heartRateBpm = 70, rest = null)) }
        assertEquals("too low to be worth it", RecoveryAtStop.NONE, viewModel.recoveryAtStop())
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

        override fun stop(recovery: RecoveryAtStop) {
            calls += "stop ${recovery.name}"
        }
    }
}
