package tech.mmarca.openwatch.wear

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.HeartRateInterval

class RecorderPlanTest {

    private val settings = WatchPreferences(
        heartRateDay = HeartRateInterval.POWER_SAVE,
        heartRateSource = WearSettings.Source.RAW_SENSOR,
        hrvSource = WearSettings.Source.RAW_SENSOR,
        syncedSleepHold = false,
        skinTemperature = true,
        spo2AtNight = true,
    )

    /** Everything granted and present, worn, daytime, no hold, no workout. */
    private val day = RecorderPlan.Inputs(
        heartRateGranted = true,
        stepsGranted = true,
        skinTemperatureGranted = true,
        oxygenSaturationGranted = true,
        settings = settings,
        hasStepCounter = true,
        hasAccelerometer = true,
        hasBarometer = true,
        worn = true,
        bedtime = false,
        sleepHolding = false,
        workout = false,
    )

    @Test
    fun `by day on the wrist the heart rate runs at the day setting from the chosen source`() {
        val plan = RecorderPlan.decide(day.copy(settings = settings.copy(heartRateSource = WearSettings.Source.SAMSUNG_PLATFORM)))

        assertEquals(HeartRateInterval.POWER_SAVE, plan.heartRate)
        assertEquals(WearSettings.Source.SAMSUNG_PLATFORM, plan.heartRateSource)
        assertTrue(plan.steps && plan.sleepMinutes && plan.ascent && plan.nightHrv && plan.spo2)
        assertFalse("skin temperature only while sleep is held", plan.skinTemperature)
    }

    @Test
    fun `bedtime mode and a sleep hold both measure at the full rate`() {
        assertEquals(HeartRateSchedule.FULL_RATE, RecorderPlan.decide(day.copy(bedtime = true)).heartRate)

        val held = RecorderPlan.decide(day.copy(sleepHolding = true))
        assertEquals(HeartRateSchedule.FULL_RATE, held.heartRate)
        assertTrue(held.skinTemperature)
    }

    @Test
    fun `off the wrist the heart rate and skin temperature rest, unless a workout runs`() {
        val resting = RecorderPlan.decide(day.copy(worn = false, sleepHolding = true))
        assertEquals(HeartRateInterval.OFF, resting.heartRate)
        assertFalse(resting.skinTemperature)
        assertTrue("the minutes and the steps go on", resting.sleepMinutes && resting.steps)

        assertEquals(HeartRateSchedule.FULL_RATE, RecorderPlan.decide(day.copy(worn = false, workout = true)).heartRate)
    }

    @Test
    fun `without the heart rate grant nothing that reads the heart sensor runs`() {
        val plan = RecorderPlan.decide(day.copy(heartRateGranted = false, sleepHolding = true, workout = true))

        assertEquals(HeartRateInterval.OFF, plan.heartRate)
        assertFalse(plan.nightHrv || plan.skinTemperature || plan.spo2)
        assertTrue("the accelerometer and the barometer need no grant", plan.sleepMinutes && plan.ascent)
    }

    @Test
    fun `steps need their grant and a counter, the others their sensor`() {
        assertFalse(RecorderPlan.decide(day.copy(stepsGranted = false)).steps)
        assertFalse(RecorderPlan.decide(day.copy(hasStepCounter = false)).steps)
        assertFalse(RecorderPlan.decide(day.copy(hasBarometer = false)).ascent)
        assertFalse(RecorderPlan.decide(day.copy(hasAccelerometer = false)).sleepMinutes)
    }

    @Test
    fun `the Samsung night measurements need their own switch and grant`() {
        val night = day.copy(sleepHolding = true)

        assertFalse(RecorderPlan.decide(night.copy(settings = settings.copy(skinTemperature = false))).skinTemperature)
        assertFalse(RecorderPlan.decide(night.copy(skinTemperatureGranted = false)).skinTemperature)
        assertFalse(RecorderPlan.decide(night.copy(settings = settings.copy(spo2AtNight = false))).spo2)
        assertFalse(RecorderPlan.decide(night.copy(oxygenSaturationGranted = false)).spo2)
    }
}
