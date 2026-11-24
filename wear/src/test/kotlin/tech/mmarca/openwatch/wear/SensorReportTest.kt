package tech.mmarca.openwatch.wear

import android.app.Application
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor

/** The debug protocol's device line: every sensor, the batching profile, and which Samsung sources are there. */
@RunWith(AndroidJUnit4::class)
class SensorReportTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `every sensor is listed with its wake-up flag and FIFO, and a watch without Samsung's sources says so`() {
        val sensors = shadowOf(app.getSystemService(SensorManager::class.java))
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_HEART_RATE).also { shadowOf(it).setWakeUpFlag(true) })
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))

        val fields = SensorReport.fields(app).toMap()

        val listed = (fields.getValue("sensors") as JSONArray).let { array -> List(array.length()) { array.get(it) as JSONObject } }
        assertEquals(
            mapOf(Sensor.STRING_TYPE_HEART_RATE to true, Sensor.STRING_TYPE_ACCELEROMETER to false),
            listed.associate { it.getString("type") to it.getBoolean("wakeUp") },
        )
        assertEquals(setOf("fifoReserved", "fifoMax"), listed.first().keys().asSequence().filter { it.startsWith("fifo") }.toSet())
        assertEquals(SensorReport.BATCHING_PROFILE, fields["batching"])
        assertEquals(false, fields["samsungPlatform"])
        assertNull(fields["samsungPlatformVersion"])
        assertEquals(false, fields["samsungHrRaw"])
        assertEquals(false, fields["samsungBedtimeSetting"])
    }

    @Test
    fun `a delay constant is reported as the period the platform turns it into`() {
        assertEquals(200_000, SensorReport.samplingMicros(SensorManager.SENSOR_DELAY_NORMAL))
        assertEquals(20_000, SensorReport.samplingMicros(SensorManager.SENSOR_DELAY_GAME))
        assertEquals(0, SensorReport.samplingMicros(SensorManager.SENSOR_DELAY_FASTEST))
        assertEquals(1_000_000, SensorReport.samplingMicros(1_000_000))
    }
}
