package tech.mmarca.openwatch.wear

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import tech.mmarca.openwatch.wear.samsung.SamsungHealthPlatform

/**
 * What this watch's sensors are, for the debug protocol: a `device` line at
 * each service start, so a report from a watch we do not have says what the
 * recorders' batching rests on there. Debuggable builds only, like the rest
 * of the protocol.
 *
 * - Every sensor: its type, name and vendor, its FIFO (reserved and maximum
 *   events; 0 is no batching at all) and whether it can wake the processor.
 * - The batching the recorders use ([BATCHING_PROFILE]). Each registration is
 *   also its own `registered` line ([DebugProtocol.registered]): the variant
 *   the recorder took, its sampling period and its report latency.
 * - Whether the Samsung sources are there: the Health Platform and its
 *   version, the raw PPG sensor the night HRV windows read, the bedtime-mode
 *   setting the sleep hold follows, and the diagnostics consent.
 */
internal object SensorReport {

    /**
     * The one batching the recorders use, tuned on the Galaxy Watch7 and
     * Watch8 sensor hub: the heart rate, the accelerometer, the barometer and
     * the step counter share a 40-second report latency, so their batches
     * arrive in one wake-up. A profile tested on another watch is still to
     * come; the `registered` lines show whether a watch's FIFOs hold a batch.
     */
    const val BATCHING_PROFILE = "hub-40s"

    /** The `device` line's fields. Reads the sensor list and the package manager: cheap, but not for the main thread of a screen. */
    fun fields(context: Context): Array<Pair<String, Any?>> {
        val sensors = (context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager)?.getSensorList(Sensor.TYPE_ALL).orEmpty()
        return arrayOf(
            "manufacturer" to Build.MANUFACTURER,
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
            "batching" to BATCHING_PROFILE,
            "sensors" to JSONArray().apply { sensors.forEach { put(describe(it)) } },
            "samsungPlatform" to SamsungHealthPlatform.isInstalled(context),
            "samsungPlatformVersion" to SamsungHealthPlatform.versionCode(context),
            "samsungHrRaw" to sensors.any { it.stringType == PpgRawLogger.SENSOR_TYPE },
            "samsungBedtimeSetting" to BedtimeMonitor.hasSamsungSetting(context),
            "samsungDiagnosticsSetting" to (SamsungDiagnostics.consentOn(context.contentResolver) != null),
        )
    }

    /** One sensor as the report has it. */
    fun describe(sensor: Sensor): JSONObject = JSONObject()
        .put("type", sensor.stringType)
        .put("name", sensor.name)
        .put("vendor", sensor.vendor)
        .put("version", sensor.version)
        .put("wakeUp", sensor.isWakeUpSensor)
        .put("fifoReserved", sensor.fifoReservedEventCount)
        .put("fifoMax", sensor.fifoMaxEventCount)
        .put("minDelayUs", sensor.minDelay)
        .put("maxDelayUs", sensor.maxDelay)
        .put("mode", sensor.reportingMode)

    /**
     * The sampling period a registration asked for, in microseconds: one of
     * the `SensorManager.SENSOR_DELAY_*` constants is turned into the period
     * it stands for, as the platform does.
     */
    fun samplingMicros(requested: Int): Int = when (requested) {
        SensorManager.SENSOR_DELAY_FASTEST -> 0
        SensorManager.SENSOR_DELAY_GAME -> 20_000
        SensorManager.SENSOR_DELAY_UI -> 66_667
        SensorManager.SENSOR_DELAY_NORMAL -> 200_000
        else -> requested
    }
}
