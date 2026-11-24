package tech.mmarca.openwatch.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Looper
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor
import tech.mmarca.openwatch.wearlink.HeartRateInterval

/** The sleep hold's default, and what counts as a choice that outlives it. */
@RunWith(AndroidJUnit4::class)
class WearSettingsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("wear_settings", Context.MODE_PRIVATE)

    @Test
    fun `without a choice the hold is synced exactly where the heart rate sensor wakes the watch`() {
        assertTrue(WearSettings.syncedSleepHoldOf(choice = null, legacySynced = false, wakeUpHeartRate = true))
        assertFalse(WearSettings.syncedSleepHoldOf(choice = null, legacySynced = false, wakeUpHeartRate = false))
    }

    @Test
    fun `a choice holds whatever the sensor, and so does a synced hold an earlier build stored`() {
        assertFalse(WearSettings.syncedSleepHoldOf(choice = "lock", legacySynced = true, wakeUpHeartRate = true))
        assertTrue(WearSettings.syncedSleepHoldOf(choice = "synced", legacySynced = false, wakeUpHeartRate = false))
        assertTrue(WearSettings.syncedSleepHoldOf(choice = null, legacySynced = true, wakeUpHeartRate = false))
    }

    @Test
    fun `the phone setting another value records no choice of the hold, changing the hold does`() {
        addHeartRateSensor(wakeUp = true)
        assertTrue(WearSettings.syncedSleepHold(context))

        WearSettings.write(context, WearSettings.read(context).copy(maxHeartRate = 182))
        assertNull(prefs.getString("sleep_hold_choice", null))
        assertTrue(WearSettings.syncedSleepHold(context))

        WearSettings.write(context, WearSettings.read(context).copy(syncedSleepHold = false))
        assertEquals("lock", prefs.getString("sleep_hold_choice", null))
        assertFalse(WearSettings.syncedSleepHold(context))
        assertFalse("an older build reads the same hold", prefs.getBoolean("synced_sleep_hold", true))
    }

    @Test
    fun `the unit system is metric until chosen, kept by name, and outlives a write of the other settings`() {
        assertEquals(UnitSystem.METRIC, WearSettings.read(context).unitSystem)

        WearSettings.write(context, WearSettings.read(context).copy(maxHeartRate = 182))
        assertNull("a write of another setting chooses no unit system", prefs.getString("unit_system", null))

        WearSettings.setUnitSystem(context, UnitSystem.IMPERIAL)
        assertEquals("IMPERIAL", prefs.getString("unit_system", null))
        WearSettings.write(context, WearSettings.read(context).copy(restingHeartRate = 52))
        assertEquals(UnitSystem.IMPERIAL, WearSettings.read(context).unitSystem)

        prefs.edit(commit = true) { putString("unit_system", "NAUTICAL") }
        assertEquals(UnitSystem.METRIC, WearSettings.unitSystem(context))
    }

    @Test
    fun `a watch whose heart rate sensor cannot wake it keeps the lock by default`() {
        addHeartRateSensor(wakeUp = false)

        assertFalse(WearSettings.syncedSleepHold(context))
        WearSettings.setSyncedSleepHold(context, true)
        assertTrue(WearSettings.syncedSleepHold(context))
    }

    @Test
    fun `the settings state follows a write from the phone and one from the watch`() {
        val settings = WearSettingsState(context)
        assertEquals(HeartRateInterval.EVERY_10_SECONDS, settings.state.value.heartRateDay)

        // The phone's SET_PREF writes every setting at once.
        WearSettings.write(context, WearSettings.read(context).copy(heartRateDay = HeartRateInterval.POWER_SAVE))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(HeartRateInterval.POWER_SAVE, settings.state.value.heartRateDay)

        WearSettings.setSpo2AtNight(context, true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(settings.state.value.spo2AtNight)
    }

    private fun addHeartRateSensor(wakeUp: Boolean) {
        val sensor = ShadowSensor.newInstance(Sensor.TYPE_HEART_RATE)
        shadowOf(sensor).setWakeUpFlag(wakeUp)
        shadowOf(context.getSystemService(SensorManager::class.java)).addSensor(sensor)
    }
}
