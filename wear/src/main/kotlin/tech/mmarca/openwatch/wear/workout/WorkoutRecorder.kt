package tech.mmarca.openwatch.wear.workout

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import java.time.Instant
import java.time.ZoneId
import kotlin.math.sqrt
import tech.mmarca.openwatch.wearlink.WorkoutSport
import tech.mmarca.openwatch.wear.AscentMinuteCounter
import tech.mmarca.openwatch.wear.DebugProtocol
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.SensorTime
import tech.mmarca.openwatch.wear.WatchPreferences
import tech.mmarca.openwatch.wear.WearMetrics
import tech.mmarca.openwatch.wear.haptics.HapticCue
import tech.mmarca.openwatch.wear.haptics.Haptics
import tech.mmarca.openwatch.wear.haptics.hapticCueForWorkoutEnd
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutLines
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

/**
 * Records one workout at a time inside `WearAppService`: GPS from the
 * platform `LocationManager`, the step counter and the barometer, with the
 * heart rate fed in by the service. [WorkoutTracker] does the arithmetic and
 * [WorkoutStore] keeps the result. No Health Services and no Google Play
 * services. Every sensor callback runs on the recorder's own thread.
 *
 * A partial wake lock is held while a workout runs, so the GPS and the
 * sensors keep delivering with the screen off. It is taken for
 * [WAKE_LOCK_MILLIS] and renewed by the recorder's once-a-second tick, so a
 * recorder thread that stopped ticking lets it lapse within ten minutes
 * instead of holding the processor until the process ends. A checkpoint every
 * [CHECKPOINT_MILLIS] lets [recoverInterrupted] save a workout whose app was
 * killed. Not yet verified on a watch.
 *
 * After a stop the wearer asked to measure it on, the heart rate recovery
 * runs for [RECOVERY_MILLIS]: [isRecovering] keeps the heart rate live
 * (`RecorderPlan`), the live value reaches the state, and the falls after one
 * and two minutes are worked out from the stored readings
 * ([WorkoutMetrics.recovery]). A stop after a minute of rest
 * ([RecoveryAtStop.FROM_REST], which [RecoveryPlan] chose) needs no two
 * minutes: the falls from the moment the arm came to rest are in the store
 * already, and are worked out at the stop. The workout itself is saved at
 * the stop; a recovery the service loses meanwhile is lost, the workout is not.
 *
 * The accelerometer runs beside the sleep minutes' own listener, at their
 * five readings a second, so the shared sensor runs no faster; while it is
 * registered without batching, the sleep minutes' readings come unbatched
 * too, which costs nothing more while the workout's wake lock holds the
 * processor. A run or a ride with a route pauses and goes on by itself
 * ([WorkoutTracker.autoPauseStep], with the `auto_pause` setting), with a
 * buzz each time. A workout started back in time, from a walk or run the
 * watch saw ([ActivityDetectionMonitor]), is credited with the steps and the
 * heart rate the store kept since its start ([WorkoutTracker.credit]).
 */
class WorkoutRecorder(
    private val context: Context,
    private val metricStore: MetricStore,
    private val state: WorkoutState,
    private val debug: DebugProtocol,
    /** The settings: the body profile and the resting heart rate the recovery is judged with. Off the main thread. */
    private val settings: () -> WatchPreferences,
    /** Called on the main thread when a workout starts or ends, so the service can adjust itself. */
    private val onActiveChange: () -> Unit,
) : SensorEventListener, LocationListener {

    private val store = WorkoutStore(MetricStoreWorkoutRows(metricStore))
    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val locations = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val mainHandler = Handler(context.mainLooper)

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var tracker: WorkoutTracker? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewedAt = 0L
    private var ascent: AscentMinuteCounter? = null
    private var lastCheckpointAt = 0L

    @Volatile
    var isActive: Boolean = false
        private set

    /** True for the two minutes of a heart rate recovery after a stop: the heart rate stays live. */
    @Volatile
    var isRecovering: Boolean = false
        private set

    private var recoveryLock: PowerManager.WakeLock? = null
    private val recoveryEnd = Runnable { finishRecovery() }

    /** True while the running workout reads the GPS: the service then holds the location foreground type. */
    @Volatile
    var usesGps: Boolean = false
        private set

    /**
     * Starts [sport]. [useGps] is false when the wearer did not allow location. A [startMillis]
     * in the past begins it then, at most [MAX_CREDIT_MILLIS] back: a walk or run the watch saw.
     * False if one is already running.
     */
    fun start(sport: WorkoutSport, useGps: Boolean, startMillis: Long? = null): Boolean {
        if (isActive) return false
        // A new workout ends a recovery still running: its two minutes are no longer a rest.
        cancelRecovery()
        isActive = true
        usesGps = useGps && sport.gps
        val worker = HandlerThread("WorkoutRecorder").apply { start() }
        val h = Handler(worker.looper)
        thread = worker
        handler = h
        val now = System.currentTimeMillis()
        val begin = startMillis?.coerceIn(now - MAX_CREDIT_MILLIS, now) ?: now
        val t = WorkoutTracker(sport, begin, usesGps, useAutoPause = settings().autoPause)
        tracker = t
        ascent = AscentMinuteCounter(null)
        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            // Not counted: each renewal moves the one timeout on, and one release ends it.
            .apply { setReferenceCounted(false) }
            .apply { acquire(WAKE_LOCK_MILLIS) }
        wakeLockRenewedAt = now
        debug.event("Workout", "start", "sport" to sport.id, "gps" to usesGps, "backMs" to now - begin)
        h.post { registerSensors(sport, now) }
        // After the registration, which may replace the tracker, and before its first reading: the counter's steps go on top.
        if (begin < now - CREDIT_AFTER_MILLIS) h.post { tracker?.let { creditEarlier(it, now) } }
        h.post(tick)
        state.update { WorkoutUiState(sport = sport, running = true, snapshot = t.snapshot(now), gps = usesGps, startMillis = begin) }
        onActiveChange()
        return true
    }

    fun pause() = post {
        debug.event("Workout", "pause")
        it.pause(System.currentTimeMillis())
        publish(it)
    }

    fun resume() = post {
        debug.event("Workout", "resume")
        it.resume(System.currentTimeMillis())
        publish(it)
    }

    fun noteHeartRate(epochMillis: Long, bpm: Int) {
        if (isRecovering) {
            state.update { it.copy(recovery = it.recovery?.copy(bpm = bpm)) }
            return
        }
        post { it.onHeartRate(epochMillis, bpm) }
    }

    /**
     * Ends the workout and saves it; a slip shorter than half a minute is dropped. With
     * [RecoveryAtStop.LIVE] a kept workout's heart rate recovery follows ([RECOVERY_MILLIS]);
     * with [RecoveryAtStop.FROM_REST] it is read from the rest before the stop.
     */
    fun stop(recovery: RecoveryAtStop = RecoveryAtStop.NONE) {
        val h = handler ?: return
        h.post { finish(recovery) }
    }

    /** Saves a workout a killed app left behind. Call once, off the main thread, when no workout runs. */
    fun recoverInterrupted() {
        if (isActive) return
        val saved = store.recover(System.currentTimeMillis())
        if (saved != null) Log.i(TAG, "Saved an interrupted workout")
    }

    /** Stops without saving anything new: the service is going away. The checkpoint stays for [recoverInterrupted]. */
    fun release() {
        cancelRecovery()
        val h = handler ?: return
        h.post { teardown(keepState = true) }
    }

    private fun post(block: (WorkoutTracker) -> Unit) {
        handler?.post { tracker?.let(block) }
    }

    /** What the store kept since a start back in time: its step minutes and its heart rate. Recorder thread. */
    private fun creditEarlier(t: WorkoutTracker, now: Long) {
        try {
            // The minute the start falls in began before it; the tracker takes its part.
            val steps = metricStore.since(WearMetrics.STEPS, t.startMillis - MINUTE_MILLIS, CREDIT_STEP_ROWS, now)
            val beats = metricStore.since(WearMetrics.HEART_RATE, t.startMillis - 1, CREDIT_HEART_RATE_ROWS, now)
                .map { WorkoutMetrics.Beat(it.epochMillis, it.bpm) }
            t.credit(steps, beats)
            debug.event("Workout", "credited", "minutes" to steps.size, "readings" to beats.size)
        } catch (e: RuntimeException) {
            // A store that cannot be read credits nothing; the workout goes on from now.
            Log.w(TAG, "Earlier minutes not credited", e)
        }
        publish(t, now)
    }

    @SuppressLint("MissingPermission")
    private fun registerSensors(sport: WorkoutSport, now: Long) {
        val h = handler ?: return
        if (sport.steps) {
            sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)?.let {
                runCatching { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, 0, h) }
                    .onSuccess { registered -> if (registered) debug.registered("workoutSteps", it, SensorManager.SENSOR_DELAY_NORMAL, 0) }
                    .onFailure { e -> Log.w(TAG, "Step counter refused", e) }
            }
        }
        // Whether the arm rests, for a recovery read from the rest before the stop.
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            if (sensors.registerListener(this, it, ACCELEROMETER_PERIOD_MICROS, 0, h)) {
                debug.registered("workoutAccel", it, ACCELEROMETER_PERIOD_MICROS, 0)
            }
        }
        if (sport.elevation) {
            sensors.getDefaultSensor(Sensor.TYPE_PRESSURE)?.let {
                if (sensors.registerListener(this, it, 1_000_000, 0, h)) debug.registered("workoutBarometer", it, 1_000_000, 0)
            }
        }
        if (usesGps) {
            try {
                locations.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_INTERVAL_MILLIS, 0f, this, h.looper)
            } catch (e: SecurityException) {
                // Revoked between the check and now: the workout goes on without a route.
                Log.w(TAG, "Location refused", e)
                usesGps = false
                tracker = tracker?.let { WorkoutTracker(it.sport, it.startMillis, useGps = false) }
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "No GPS provider", e)
            }
        }
        publish(tracker ?: return, now)
    }

    private val tick = object : Runnable {
        override fun run() {
            val t = tracker ?: return
            val now = System.currentTimeMillis()
            // The phone refuses a longer workout, so end it here instead of losing it.
            if (now - t.startMillis >= WearLinkWorkoutLines.MAX_DURATION_MILLIS) {
                finish()
                return
            }
            if (t.autoPauseStep(now)) {
                val paused = t.isPaused
                debug.event("Workout", if (paused) "auto_pause" else "auto_resume")
                Haptics.play(context, if (paused) HapticCue.PULSE else HapticCue.DOUBLE_PULSE)
            }
            publish(t, now)
            if (now - wakeLockRenewedAt >= WAKE_LOCK_RENEW_MILLIS) {
                wakeLockRenewedAt = now
                wakeLock?.acquire(WAKE_LOCK_MILLIS)
            }
            if (now - lastCheckpointAt >= CHECKPOINT_MILLIS) {
                lastCheckpointAt = now
                store.saveCheckpoint(t.checkpoint(now))
            }
            handler?.postDelayed(this, 1_000)
        }
    }

    private fun publish(t: WorkoutTracker, now: Long = System.currentTimeMillis()) {
        val snapshot = t.snapshot(now)
        state.update { it.copy(snapshot = snapshot, gps = usesGps) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val t = tracker ?: return
        val at = SensorTime.epochMillisOf(event.timestamp)
        when (event.sensor.type) {
            Sensor.TYPE_STEP_COUNTER -> event.values.firstOrNull()?.let { t.onStepTotal(at, it.toLong()) }
            Sensor.TYPE_ACCELEROMETER -> {
                if (SensorTime.isStale(at) || event.values.size < AXES) return
                val (x, y, z) = event.values
                t.onAcceleration(at, sqrt(x * x + y * y + z * z).toDouble())
            }
            Sensor.TYPE_PRESSURE -> {
                val hpa = event.values.firstOrNull() ?: return
                if (SensorTime.isStale(at)) return
                ascent?.onPressure(at, hpa)?.let(t::onAscentMinute)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        val t = tracker ?: return
        val fix = WorkoutFix(
            epochMillis = location.time,
            latitude = location.latitude,
            longitude = location.longitude,
            altitudeMetres = if (location.hasAltitude()) location.altitude else null,
            accuracyMetres = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
            speedMetresPerSecond = if (location.hasSpeed()) location.speed.toDouble() else null,
        )
        t.onFix(fix)?.let(store::savePoint)
    }

    private fun finish(atStop: RecoveryAtStop = RecoveryAtStop.NONE) {
        val t = tracker ?: return
        val end = System.currentTimeMillis()
        // As of the stop: the screen chose it from a snapshot of a second ago.
        val rest = t.restRecovery(end).takeIf { atStop == RecoveryAtStop.FROM_REST }
        val workout = t.finish(end)
        val kept = store.finish(workout, end)
        val last = t.snapshot(end)
        debug.event(
            "Workout", "stop",
            "sport" to t.sport.id, "durationMs" to workout.endMillis - workout.startMillis, "steps" to workout.steps, "saved" to kept,
        )
        val gps = usesGps
        val recovery = when {
            !kept -> null
            rest != null -> fromRest(rest)
            atStop == RecoveryAtStop.LIVE -> RecoveryState(end, end + RECOVERY_MILLIS)
            else -> null
        }
        if (recovery?.measuring == true) beginRecovery()
        teardown(keepState = false)
        state.update {
            WorkoutUiState(sport = t.sport, running = false, snapshot = last, finished = kept, gps = gps, startMillis = t.startMillis, recovery = recovery)
        }
        if (!kept) state.clearFinished()
        Haptics.play(context, hapticCueForWorkoutEnd(kept))
    }

    /** A recovery from the rest before the stop: its falls, from the moment the arm came to rest, are in the store already. */
    private fun fromRest(rest: RestRecovery): RecoveryState {
        val recovery = RecoveryState(rest.startMillis, rest.startMillis + RECOVERY_MILLIS)
        val falls = try {
            recoveryFalls(recovery)
        } catch (e: RuntimeException) {
            // A store that cannot be read gives no falls; the workout is saved all the same.
            Log.w(TAG, "Recovery not worked out", e)
            WorkoutMetrics.Recovery(null, null)
        }
        return recovery.copy(falls = falls)
    }

    /** The recovery's two minutes start: the heart rate stays live, and the processor awake until they end. */
    private fun beginRecovery() {
        isRecovering = true
        recoveryLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, RECOVERY_WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
            .apply { acquire(RECOVERY_MILLIS + RECOVERY_SETTLE_MILLIS + WAKE_LOCK_SLACK_MILLIS) }
        mainHandler.postDelayed(recoveryEnd, RECOVERY_MILLIS + RECOVERY_SETTLE_MILLIS)
        debug.event("Workout", "recovery_start")
    }

    /**
     * The two minutes are over: the falls from the readings stored around them, on a thread of
     * their own since the store is read, and the heart rate back to its plan.
     */
    private fun finishRecovery() {
        val recovery = state.state.value.recovery?.takeIf { it.measuring } ?: return endRecovery()
        Thread({
            val falls = try {
                recoveryFalls(recovery)
            } catch (e: RuntimeException) {
                // A store that cannot be read gives no falls; the recovery ends all the same.
                Log.w(TAG, "Recovery not worked out", e)
                WorkoutMetrics.Recovery(null, null)
            }
            state.update { now ->
                if (now.recovery?.stopMillis != recovery.stopMillis) now else now.copy(recovery = now.recovery.copy(falls = falls))
            }
            mainHandler.post(::endRecovery)
        }, "WorkoutRecovery").start()
    }

    /** The falls of [recovery] from the heart rate stored around it, judged with the profile the phone sent. Reads the store. */
    private fun recoveryFalls(recovery: RecoveryState): WorkoutMetrics.Recovery {
        val beats = metricStore.since(
            WearMetrics.HEART_RATE, recovery.stopMillis - RECOVERY_LOOKBACK_MILLIS, RECOVERY_ROWS, recovery.untilMillis + RECOVERY_SETTLE_MILLIS,
        ).map { WorkoutMetrics.Beat(it.epochMillis, it.bpm) }
        val prefs = settings()
        val day = Instant.ofEpochMilli(recovery.stopMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        val falls = WorkoutMetrics.recovery(beats, recovery.stopMillis, prefs.bodyProfile(), prefs.restingHeartRate, day)
        debug.event("Workout", "recovery_end", "readings" to beats.size, "measured" to (falls.oneMinuteBpm != null))
        return falls
    }

    /** A recovery still measuring ends without its falls: a new workout began, or the service is going away. */
    private fun cancelRecovery() {
        if (!isRecovering) return
        mainHandler.removeCallbacks(recoveryEnd)
        state.update { it.copy(recovery = it.recovery?.let { recovery -> recovery.copy(falls = recovery.falls ?: WorkoutMetrics.Recovery(null, null)) }) }
        endRecovery()
    }

    private fun endRecovery() {
        if (!isRecovering) return
        isRecovering = false
        recoveryLock?.takeIf { it.isHeld }?.release()
        recoveryLock = null
        onActiveChange()
    }

    private fun teardown(keepState: Boolean) {
        sensors.unregisterListener(this)
        runCatching { locations.removeUpdates(this) }
        handler?.removeCallbacksAndMessages(null)
        tracker = null
        ascent = null
        isActive = false
        usesGps = false
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        thread?.quitSafely()
        thread = null
        handler = null
        if (keepState) state.update { WorkoutUiState() }
        mainHandler.post(onActiveChange)
    }

    private companion object {
        const val TAG = "WorkoutRecorder"
        const val GPS_INTERVAL_MILLIS = 1_000L
        const val CHECKPOINT_MILLIS = 10_000L

        /** The app's name, so the system's wake lock list says whose it is. */
        const val WAKE_LOCK_TAG = "OpenWatch:workout"

        /** How long one hold lasts if the tick stops renewing it. */
        const val WAKE_LOCK_MILLIS = 10L * 60 * 1000

        /** The tick renews the hold once a minute. */
        const val WAKE_LOCK_RENEW_MILLIS = 60_000L

        /** Two minutes after the stop: the fall after one is the one Cole et al. judged, the second shows its course. */
        const val RECOVERY_MILLIS = 2 * 60_000L

        /** Past the two minutes, so the last mark's readings (health-calculations takes one within 5 s) are stored. */
        const val RECOVERY_SETTLE_MILLIS = 10_000L

        /** Before the stop, where the recovery's peak is read. */
        const val RECOVERY_LOOKBACK_MILLIS = 60_000L

        /** Far above three minutes of readings at one a second. */
        const val RECOVERY_ROWS = 1_000

        const val RECOVERY_WAKE_LOCK_TAG = "OpenWatch:recovery"
        const val WAKE_LOCK_SLACK_MILLIS = 10_000L

        const val MINUTE_MILLIS = 60_000L

        /** A start further back than this is a workout taken from the stored minutes, not one begun now. */
        const val CREDIT_AFTER_MILLIS = MINUTE_MILLIS

        /** The furthest back a workout may begin: what `ActivityDetectionMonitor` looks back over. */
        const val MAX_CREDIT_MILLIS = 3 * 60 * MINUTE_MILLIS

        /** A row a minute over [MAX_CREDIT_MILLIS], and a heart rate a second. */
        const val CREDIT_STEP_ROWS = (MAX_CREDIT_MILLIS / MINUTE_MILLIS).toInt() + 2
        const val CREDIT_HEART_RATE_ROWS = (MAX_CREDIT_MILLIS / 1_000).toInt() + 1

        /** Five readings a second, the sleep minutes' own rate (`SleepMinuteRecorder`): the shared sensor runs no faster. */
        const val ACCELEROMETER_PERIOD_MICROS = 200_000
        const val AXES = 3
    }
}
