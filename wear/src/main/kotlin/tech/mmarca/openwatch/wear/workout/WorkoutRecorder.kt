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
import tech.mmarca.openwatch.wearlink.WorkoutSport
import tech.mmarca.openwatch.wear.AscentMinuteCounter
import tech.mmarca.openwatch.wear.DebugProtocol
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.SensorTime
import tech.mmarca.openwatch.wear.haptics.Haptics
import tech.mmarca.openwatch.wear.haptics.hapticCueForWorkoutEnd
import tech.mmarca.openwatch.wearlink.WearLinkWorkoutLines

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
 */
class WorkoutRecorder(
    private val context: Context,
    metricStore: MetricStore,
    private val state: WorkoutState,
    private val debug: DebugProtocol,
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

    /** True while the running workout reads the GPS: the service then holds the location foreground type. */
    @Volatile
    var usesGps: Boolean = false
        private set

    /** Starts [sport]. [useGps] is false when the wearer did not allow location. False if one is already running. */
    fun start(sport: WorkoutSport, useGps: Boolean): Boolean {
        if (isActive) return false
        isActive = true
        usesGps = useGps && sport.gps
        val worker = HandlerThread("WorkoutRecorder").apply { start() }
        val h = Handler(worker.looper)
        thread = worker
        handler = h
        val now = System.currentTimeMillis()
        val t = WorkoutTracker(sport, now, usesGps)
        tracker = t
        ascent = AscentMinuteCounter(null)
        wakeLock = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            // Not counted: each renewal moves the one timeout on, and one release ends it.
            .apply { setReferenceCounted(false) }
            .apply { acquire(WAKE_LOCK_MILLIS) }
        wakeLockRenewedAt = now
        debug.event("Workout", "start", "sport" to sport.id, "gps" to usesGps)
        h.post { registerSensors(sport, now) }
        h.post(tick)
        state.update { WorkoutUiState(sport = sport, running = true, snapshot = t.snapshot(now), gps = usesGps) }
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

    fun noteHeartRate(epochMillis: Long, bpm: Int) = post { it.onHeartRate(epochMillis, bpm) }

    /** Ends the workout and saves it; a slip shorter than half a minute is dropped. */
    fun stop() {
        val h = handler ?: return
        h.post { finish() }
    }

    /** Saves a workout a killed app left behind. Call once, off the main thread, when no workout runs. */
    fun recoverInterrupted() {
        if (isActive) return
        val saved = store.recover(System.currentTimeMillis())
        if (saved != null) Log.i(TAG, "Saved an interrupted workout")
    }

    /** Stops without saving anything new: the service is going away. The checkpoint stays for [recoverInterrupted]. */
    fun release() {
        val h = handler ?: return
        h.post { teardown(keepState = true) }
    }

    private fun post(block: (WorkoutTracker) -> Unit) {
        handler?.post { tracker?.let(block) }
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

    private fun finish() {
        val t = tracker ?: return
        val end = System.currentTimeMillis()
        val workout = t.finish(end)
        val kept = store.finish(workout, end)
        val last = t.snapshot(end)
        debug.event(
            "Workout", "stop",
            "sport" to t.sport.id, "durationMs" to workout.endMillis - workout.startMillis, "steps" to workout.steps, "saved" to kept,
        )
        val gps = usesGps
        teardown(keepState = false)
        state.update { WorkoutUiState(sport = t.sport, running = false, snapshot = last, finished = kept, gps = gps) }
        if (!kept) state.clearFinished()
        Haptics.play(context, hapticCueForWorkoutEnd(kept))
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
    }
}
