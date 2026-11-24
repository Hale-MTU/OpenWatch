package tech.mmarca.openwatch.wear.workout

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tech.mmarca.healthcalculations.preferences.BiologicalSex
import tech.mmarca.openwatch.wear.MeasurementSource
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearMetrics
import tech.mmarca.openwatch.wearlink.HeartRateInterval
import tech.mmarca.openwatch.wearlink.WearLinkProtocol.HeartRateSample
import tech.mmarca.openwatch.wearlink.WearLinkWorkout
import tech.mmarca.openwatch.wearlink.WorkoutSport

/** A saved workout's figures on the watch: from the store's workout and heart rate, with the phone's zones and profile. */
@RunWith(AndroidJUnit4::class)
class WorkoutFiguresTest {

    private val store = MetricStore(ApplicationProvider.getApplicationContext(), name = null)
    private val start = 1_794_000_000_000L
    private var settings = WatchPreferences(
        heartRateDay = HeartRateInterval.EVERY_10_SECONDS,
        heartRateSource = MeasurementSource.RAW_SENSOR,
        hrvSource = MeasurementSource.RAW_SENSOR,
        syncedSleepHold = false,
        skinTemperature = false,
        spo2AtNight = false,
        maxHeartRate = 190,
        restingHeartRate = 50,
    )
    private val figures = WorkoutFigures(store, settings = { settings }, zone = { ZoneOffset.UTC })

    @After
    fun close() = store.close()

    private fun save(minutes: Int) {
        val workout = WearLinkWorkout(start, start + minutes * 60_000L, WorkoutSport.RUNNING.exerciseType, null, null, null)
        assertTrue(WorkoutStore(MetricStoreWorkoutRows(store)).finish(workout, start + minutes * 60_000L))
    }

    @Test
    fun `the figures rest on the workout's own heart rate, its zones and the phone's profile`() {
        save(minutes = 10)
        // Ten minutes at 120 bpm, one a second as the workout records it, and a reading before it at 180.
        store.putAll(WearMetrics.HEART_RATE, listOf(HeartRateSample(start - 60_000, 180)) + (0..600).map { HeartRateSample(start + it * 1_000L, 120) })
        settings = settings.copy(weightHectograms = 700, heightCm = 175, birthYear = 1996, sex = BiologicalSex.MALE)

        val summary = figures.of(start)!!

        assertEquals(120, summary.maxBpm)
        // 120 bpm on a resting 50 and a maximum 190 is half the reserve: zone 3, the whole ten minutes.
        assertEquals(listOf(0L, 0L, 600L, 0L, 0L), summary.zoneSeconds)
        assertEquals(8.36, summary.trimp!!, 0.01)
        assertTrue("the profile gives the energy", summary.activeKcal != null)
    }

    @Test
    fun `without the phone's maximum and profile there are no zones, no load and no energy`() {
        save(minutes = 10)
        store.putAll(WearMetrics.HEART_RATE, (0..600).map { HeartRateSample(start + it * 1_000L, 120) })
        settings = settings.copy(maxHeartRate = null, restingHeartRate = null)

        val summary = figures.of(start)!!

        assertEquals(List(5) { 0L }, summary.zoneSeconds)
        assertNull(summary.trimp)
        assertNull(summary.activeKcal)
        assertNull("no workout began then", figures.of(start + 1))
    }
}
