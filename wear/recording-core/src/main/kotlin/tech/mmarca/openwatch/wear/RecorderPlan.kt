package tech.mmarca.openwatch.wear

import tech.mmarca.openwatch.wearlink.HeartRateInterval

/**
 * Which recorders run, and how, from the wearer's settings, the grants, the
 * sensors the watch has and the state of the wrist and the night. Pure, so
 * the service's policy is tested like [HeartRateSchedule]: [WearAppService]
 * gathers the [Inputs], [decide]s, and hands the [Plan] to every [Recorder]
 * in [WearGraph.recorders].
 */
object RecorderPlan {

    data class Inputs(
        val heartRateGranted: Boolean,
        val stepsGranted: Boolean,
        val skinTemperatureGranted: Boolean,
        val oxygenSaturationGranted: Boolean,
        val settings: WatchPreferences,
        val hasStepCounter: Boolean,
        val hasAccelerometer: Boolean,
        val hasBarometer: Boolean,
        /** False while [WornMonitor] says the watch is off the wrist. */
        val worn: Boolean,
        /** Bedtime mode, as [BedtimeMonitor] reads it. */
        val bedtime: Boolean,
        /** The sleep recorder holds sleep ([SleepMinuteRecorder.isSleepHolding]), from bedtime mode or [SleepHold]. */
        val sleepHolding: Boolean,
        val workout: Boolean,
        /**
         * The two minutes of a heart rate recovery after a workout's stop: the heart rate
         * stays as it was during the workout, live, so each of the recovery's marks has a
         * reading within its seconds.
         */
        val recovering: Boolean = false,
    )

    data class Plan(
        /** The interval in force, [HeartRateInterval.OFF] for none. */
        val heartRate: HeartRateInterval,
        /** The source asked for; the recorder falls back to the sensor when the platform fails. */
        val heartRateSource: MeasurementSource,
        /**
         * The heart rate as it happens: the sensor unbatched, every reading kept, one a second.
         * During a workout, whose zones, live value and figures read the whole curve; the
         * day's batches hand it over up to 40 seconds late, the Samsung platform's minutes
         * late with the screen off.
         */
        val heartRateLive: Boolean,
        val steps: Boolean,
        val sleepMinutes: Boolean,
        val ascent: Boolean,
        /** The night HRV windows may run; each window still waits for a still arm in a sleep hold. */
        val nightHrv: Boolean,
        val skinTemperature: Boolean,
        /** SpO2 spot measurements may run; each one still waits for a sleep hold. */
        val spo2: Boolean,
    )

    fun decide(inputs: Inputs): Plan {
        val granted = inputs.heartRateGranted
        val settings = inputs.settings
        val active = inputs.workout || inputs.recovering
        // The sensor rests off the wrist, except for a workout and its recovery, which record at the full rate.
        val heartRate = if (granted && (inputs.worn || active)) {
            HeartRateSchedule.interval(
                heartRate = true,
                sleepRecording = true,
                // The sleep minutes read every sample while sleep is held.
                bedtime = inputs.bedtime || inputs.sleepHolding,
                day = settings.heartRateDay,
                night = settings.heartRateDay,
                workout = active,
            )
        } else {
            HeartRateInterval.OFF
        }
        // Skin temperature comes only from the Samsung platform, and only while sleep is held on the wrist.
        val night = granted && inputs.worn && inputs.sleepHolding
        val live = heartRate != HeartRateInterval.OFF && active
        return Plan(
            heartRate = heartRate,
            // A workout and its recovery read the sensor, whatever the source setting: only the sensor can be read live.
            heartRateSource = if (live) MeasurementSource.RAW_SENSOR else settings.heartRateSource,
            heartRateLive = live,
            steps = inputs.stepsGranted && inputs.hasStepCounter,
            // The accelerometer and the barometer are open to every app.
            sleepMinutes = inputs.hasAccelerometer,
            ascent = inputs.hasBarometer,
            // The raw PPG sits under the heart rate grant.
            nightHrv = granted,
            skinTemperature = night && settings.skinTemperature && inputs.skinTemperatureGranted,
            spo2 = granted && settings.spo2AtNight && inputs.oxygenSaturationGranted,
        )
    }
}

/**
 * A recorder the service runs: it follows the [RecorderPlan.Plan] in force
 * and stops with the service. [WearGraph] builds each once per process; the
 * service starts, changes and stops them through this interface alone.
 */
interface Recorder {
    val isRunning: Boolean

    /** Starts, changes or stops what this recorder runs to match [plan]. Main thread. */
    fun apply(plan: RecorderPlan.Plan)

    fun stop()
}
