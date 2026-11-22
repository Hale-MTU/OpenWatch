package tech.mmarca.openwatch.wear

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import tech.mmarca.openwatch.wearlink.HeartRateInterval
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * The heart rate from the Android sensor (`TYPE_HEART_RATE`), the wake-up
 * variant where the watch has one. One way of running per interval:
 * - [HeartRateInterval.EVERY_10_SECONDS]: the sensor stays registered, batched:
 *   the sensor hub buffers readings for up to [MAX_REPORT_LATENCY_MICROS] and
 *   wakes the CPU once per batch, and every other sensor's buffer is emptied in
 *   the same wake-up ([SensorWakeSync]).
 * - [HeartRateInterval.POWER_SAVE]: the sensor is registered for a window of
 *   at most [WINDOW_CAP_MILLIS], unbatched, and is unregistered at its first
 *   usable reading. Between the windows the sensor, and usually the CPU, are
 *   off: `Handler.postDelayed` does not fire while the CPU sleeps, so the next
 *   window is scheduled with [AlarmManager.setAndAllowWhileIdle], through
 *   [HeartRateAlarmReceiver] and `WearAppService`, which call [onAlarm].
 *
 * Everything but [onAlarm] runs on the handler [start] was given.
 */
internal class SensorHeartRateSource(
    private val context: Context,
    private val debug: DebugProtocol,
    /** Told at each batch, so every sensor's buffer is emptied in the same wake-up. */
    private val wakeSync: SensorWakeSync? = null,
) : HeartRateSource, SensorEventListener {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
        .apply { setReferenceCounted(false) }
    private val alarmPendingIntent: PendingIntent by lazy {
        PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            Intent(context, HeartRateAlarmReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Set by [start], cleared by [stop]. */
    @Volatile
    private var handler: Handler? = null

    @Volatile
    private var interval: HeartRateInterval = HeartRateInterval.OFF
    private var sink: HeartRateSource.Sink? = null
    private var sensor: Sensor? = null
    private var lastArrivalAt = 0L
    private var lastSkipLogAt = 0L

    // Read and written on the handler's thread only. The tasks are fields:
    // removing a timer needs the same Runnable that posted it.
    private var windowOpen = false
    private var windowOpenedAt = 0L
    private var windowValues = 0
    private val openWindowTask = Runnable { openWindow() }
    private val closeWindowTask = Runnable { closeWindow() }

    /** The wake-up variant empties its batch even while the watch sleeps; the other hands over only what survived. */
    private fun defaultSensor(): Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE, true) ?: sensorManager.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    override val isAvailable: Boolean
        get() = defaultSensor() != null

    override fun start(interval: HeartRateInterval, handler: Handler, sink: HeartRateSource.Sink): Boolean {
        val found = defaultSensor() ?: return false
        this.handler = handler
        this.sink = sink
        this.interval = interval
        sensor = found
        if (interval == HeartRateInterval.EVERY_10_SECONDS) {
            if (!registerBatched(found, handler)) {
                stop()
                return false
            }
        } else {
            handler.post(openWindowTask)
        }
        return true
    }

    override fun stop() {
        // Pending timers would reopen the sensor after this returns.
        handler?.removeCallbacks(openWindowTask)
        handler?.removeCallbacks(closeWindowTask)
        alarmManager.cancel(alarmPendingIntent)
        sensorManager.unregisterListener(this)
        if (wakeLock.isHeld) wakeLock.release()
        windowOpen = false
        handler = null
        sink = null
        sensor = null
        interval = HeartRateInterval.OFF
    }

    /**
     * An alarm fired while the source runs at [HeartRateInterval.POWER_SAVE].
     * Only posts to the source's handler. A no-op once the interval has since
     * changed: cancelling the alarm in [stop] races the alarm already having fired.
     */
    override fun onAlarm() {
        val workerHandler = handler ?: return
        if (interval != HeartRateInterval.POWER_SAVE) return
        workerHandler.post(openWindowTask)
    }

    /** Registration for the continuous interval: one reading a second, batched. */
    private fun registerBatched(found: Sensor, workerHandler: Handler): Boolean =
        try {
            sensorManager.registerListener(this, found, SAMPLING_PERIOD_MICROS, MAX_REPORT_LATENCY_MICROS, workerHandler)
                .also { if (it) debug.registered("heartRate", found, SAMPLING_PERIOD_MICROS, MAX_REPORT_LATENCY_MICROS) }
        } catch (e: SecurityException) {
            // The grant was revoked between the check and the registration.
            Log.e(TAG, "Heart rate sensor refused", e)
            false
        }

    /** Starts a window: the sensor on, until its first usable reading or [WINDOW_CAP_MILLIS]. */
    private fun openWindow() {
        val workerHandler = handler ?: return
        val found = sensor ?: return
        windowOpenedAt = SystemClock.elapsedRealtime()
        windowValues = 0
        wakeLock.acquire(WINDOW_CAP_MILLIS + WAKE_LOCK_SLACK_MILLIS)
        val registered = try {
            sensorManager.registerListener(this, found, SAMPLING_PERIOD_MICROS, 0, workerHandler)
                .also { if (it) debug.registered("heartRateWindow", found, SAMPLING_PERIOD_MICROS, 0) }
        } catch (e: SecurityException) {
            Log.e(TAG, "Heart rate sensor refused", e)
            false
        }
        if (!registered) {
            Log.w(TAG, "Heart rate window not registered")
            debug.event("HeartRate", "window_failed")
            if (wakeLock.isHeld) wakeLock.release()
            scheduleNextWindow()
            return
        }
        windowOpen = true
        workerHandler.postDelayed(closeWindowTask, WINDOW_CAP_MILLIS)
    }

    /**
     * Ends the open window: the sensor off, and the next window scheduled.
     * Does nothing when no window is open, so the reading path and the
     * timeout cannot both schedule the next one.
     */
    private fun closeWindow() {
        if (!windowOpen) return
        handler?.removeCallbacks(closeWindowTask)
        windowOpen = false
        sensorManager.unregisterListener(this)
        if (wakeLock.isHeld) wakeLock.release()
        val duration = SystemClock.elapsedRealtime() - windowOpenedAt
        debug.count("hr.windows")
        debug.count("hr.windowMs", duration)
        if (windowValues == 0) debug.count("hr.windowsEmpty")
        scheduleNextWindow()
    }

    /**
     * Schedules the next window: [AlarmManager.setAndAllowWhileIdle] fires
     * [HeartRateAlarmReceiver] even while the watch is asleep, unlike
     * `Handler.postDelayed`. The wait is the interval minus the time the
     * window already used, never negative.
     */
    private fun scheduleNextWindow() {
        val elapsed = SystemClock.elapsedRealtime() - windowOpenedAt
        val wait = nextWindowDelayMillis(elapsed, interval.seconds)
        alarmManager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + wait, alarmPendingIntent)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_HEART_RATE) return
        val target = sink ?: return
        debug.arrival("hr")
        val arrived = SystemClock.elapsedRealtime()
        if (arrived - lastArrivalAt > BATCH_GAP_MILLIS && interval == HeartRateInterval.EVERY_10_SECONDS) wakeSync?.onWake()
        lastArrivalAt = arrived
        // No contact and unreliable readings carry a rate of zero or a flag; both are skipped.
        val bpm = event.values.firstOrNull()?.toInt() ?: return
        val at = SensorTime.epochMillisOf(event.timestamp)
        // The cached reading handed to a new listener carries the time the sensor last ran.
        if (SensorTime.isStale(at)) return
        if (!isUsableHeartRate(event.accuracy, bpm)) {
            noteSkipped()
            target.onNoContact(at)
            return
        }
        if (windowOpen) windowValues++
        target.onReading(at, bpm)
        // One reading per window is all a power-save window needs: the sensor goes off now.
        if (interval == HeartRateInterval.POWER_SAVE && windowOpen) closeWindow()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Off the wrist the sensor keeps reporting; one line a minute says so without flooding. */
    private fun noteSkipped() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSkipLogAt < SKIP_LOG_EVERY_MILLIS) return
        lastSkipLogAt = now
        Log.d(TAG, "Skipping readings: no contact or unreliable")
    }

    private companion object {
        const val TAG = "SensorHeartRateSource"
        const val WAKE_LOCK_TAG = "OpenWatch:heartRatePowerSave"

        /** One reading a second from the sensor; the recorder keeps one in ten. */
        const val SAMPLING_PERIOD_MICROS = 1_000_000

        /**
         * How long the sensor hub may hold readings before waking the CPU.
         * This is the wake-up sensor, so it sets how often the watch wakes at
         * night, and that must fit inside the accelerometer's buffer: that
         * sensor cannot wake the watch, its buffer holds 300 readings, 48
         * seconds at the 6.25 Hz the hub delivers, and everything older is
         * lost. At a minute the first night kept 30 readings a minute.
         */
        const val MAX_REPORT_LATENCY_MICROS = 40 * 1_000_000

        /**
         * The safety ceiling on a power-save window: the most time the sensor
         * stays on if no usable reading ever arrives. On-device, a reading
         * usually closes the window within a few seconds.
         */
        const val WINDOW_CAP_MILLIS = 20_000L

        /** Held past [WINDOW_CAP_MILLIS] so the lock cannot expire mid-window on a slow start. */
        const val WAKE_LOCK_SLACK_MILLIS = 10_000L

        const val ALARM_REQUEST_CODE = 1
        const val SKIP_LOG_EVERY_MILLIS = 60_000L

        /** Readings closer than this arrived in one batch, one wake-up. */
        const val BATCH_GAP_MILLIS = 2_000L
    }
}

/** False for no contact, an unreliable reading, or a rate outside what a heart does. */
internal fun isUsableHeartRate(accuracy: Int, bpm: Int): Boolean =
    accuracy != SensorManager.SENSOR_STATUS_NO_CONTACT &&
        accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE &&
        bpm in WearLinkProtocol.MIN_BPM..WearLinkProtocol.MAX_BPM

/**
 * How long to wait, from now, before a power-save window should next open:
 * the interval minus the time the window just used, never negative. Pure, so
 * it is tested without the real clock or [android.app.AlarmManager].
 */
internal fun nextWindowDelayMillis(elapsedSinceOpenMillis: Long, intervalSeconds: Int): Long =
    (intervalSeconds * 1000L - elapsedSinceOpenMillis).coerceAtLeast(0L)

/**
 * Wakes the watch for one power-save heart rate window. `AlarmManager`
 * delivers this while the CPU is asleep; it reaches the running
 * `WearAppService` through an action on the foreground service, never a
 * static reference to the recorder itself.
 */
class HeartRateAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            context.startForegroundService(
                Intent(context, WearAppService::class.java).setAction(WearAppService.ACTION_HEART_RATE_ALARM),
            )
        }.onFailure { Log.e("HeartRateAlarmReceiver", "Cannot reach the service for the heart rate alarm", it) }
    }
}
