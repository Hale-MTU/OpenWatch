package tech.mmarca.openwatch.wear

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.StateFlow
import tech.mmarca.openwatch.wearlink.Page
import tech.mmarca.openwatch.wearlink.WearLinkWorkout
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateFeed
import tech.mmarca.openwatch.wear.samsung.SamsungHeartRateSource
import tech.mmarca.openwatch.wear.samsung.SamsungIbiSource
import tech.mmarca.openwatch.wear.samsung.SamsungSpotSource
import tech.mmarca.openwatch.wear.workout.MetricStoreWorkoutRows
import tech.mmarca.openwatch.wear.workout.WorkoutRecorder
import tech.mmarca.openwatch.wear.workout.WorkoutState
import tech.mmarca.openwatch.wear.workout.WorkoutStore

/**
 * Everything the watch app runs on, built by hand once per process
 * ([OpenWatchWearApplication]): the store and the entry log over it, the
 * link's state and trust, the recorders and the sources they share, the link
 * server and the debug protocol. The watch has no DI framework; this file is its wiring, and
 * nothing else builds one of these parts.
 *
 * A part is built on first use, so a tile that only reads the store builds
 * nothing else. The recorders live as long as the process: [WearAppService]
 * starts, changes and stops them, and is the [host] they tell, on the main
 * thread, when they change what the plan rests on.
 */
internal class WearGraph(private val app: Application) {

    /** What the running service does when a part of the graph changes an input of its plan. Main thread. */
    interface Host {
        /** The sleep hold or the heart rate's source changed: the plan is decided again. */
        fun replan()

        /** [WornMonitor] says the heart rate should run, or rest off the wrist. */
        fun onWornChanged(wanted: Boolean)

        /** A workout started or ended: the foreground types and the plan follow. */
        fun onWorkoutChanged()

        /** The phone changed a setting: the recorders follow as for one changed on the watch. */
        fun onSettingsChanged()
    }

    /** The service while it runs; null otherwise, and then nothing is told. */
    @Volatile
    var host: Host? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun tellHost(action: Host.() -> Unit) {
        mainHandler.post { host?.action() }
    }

    val debug: DebugProtocol by lazy { DebugProtocol.of(app) }
    val linkState = WearLinkState()
    val workoutState = WorkoutState()
    val secretKey = WearSecretKey()
    val phoneRequests: WearPhoneRequests by lazy { WearPhoneRequests(app) }
    val store: MetricStore by lazy { MetricStore(app) }

    /** The entries made on the watch, by hand or by a spot measurement: their one writer. */
    val entries: EntryLog by lazy { EntryLog(store) }

    /** The settings as they change, on the watch or from the phone. The first read loads their file: off the main thread. */
    val settings: StateFlow<WatchPreferences> by lazy { WearSettingsState(app).state }

    val trust: WearTrustStore by lazy {
        WearTrustStore(app, linkState, secretKey).also { it.onPending = { pending -> phoneRequests.notify(pending) } }
    }

    val samsungFeed: SamsungHeartRateFeed by lazy { SamsungHeartRateFeed(app, debug) }
    val wakeSync: SensorWakeSync by lazy { SensorWakeSync(app, debug) }

    /*
     * The sources behind their interfaces: the raw sensors everywhere, the
     * Samsung platform where the watch has it. The recorders see only the
     * interfaces.
     */
    private val sensorHeartRate: HeartRateSource by lazy { SensorHeartRateSource(app, debug, wakeSync) }
    private val samsungHeartRate: HeartRateSource? by lazy { SamsungHeartRateSource(samsungFeed).takeIf { it.isAvailable } }
    private val ppgWindows: HrvWindowSource by lazy { PpgWindowSource(app, debug) }
    private val samsungWindows: HrvWindowSource by lazy { SamsungIbiSource(samsungFeed, debug) }
    private val spo2Source: SpotSource<Spo2Reading> by lazy { SamsungSpotSource.spo2(samsungFeed) }
    private val skinSource: SpotSource<SkinReading> by lazy { SamsungSpotSource.skinTemperature(samsungFeed) }

    val heartRate: HeartRateRecorder by lazy {
        HeartRateRecorder(
            store,
            sensor = sensorHeartRate,
            platform = samsungHeartRate,
            debug = debug,
            onSourceChanged = { tellHost { replan() } },
            onSample = { at, bpm ->
                sleepMinutes.noteHeartRate(at, bpm)
                if (workout.isActive) workout.noteHeartRate(at, bpm)
            },
            onContact = { at, contact -> sleepMinutes.noteHeartRateContact(at, contact) },
        )
    }

    val sleepMinutes: SleepMinuteRecorder by lazy {
        SleepMinuteRecorder(
            app,
            store,
            linkState,
            debug,
            isHeartRateRecording = { heartRate.isDense },
            stepsInMinute = ::stepsInMinute,
            // The heart rate runs at full rate while sleep is held; the plan reads it.
            onSleepHoldChange = { tellHost { replan() } },
            wakeSync = wakeSync,
            syncedHold = { heartRate.isDense && WearSettings.syncedSleepHold(app) },
        )
    }

    val steps: StepRecorder by lazy { StepRecorder(app, store, debug, wakeSync, heartRateDense = { heartRate.isDense }) }
    val ascent: AscentRecorder by lazy { AscentRecorder(app, store, debug, wakeSync) }

    val nightHrv: NightHrvRecorder by lazy {
        NightHrvRecorder(
            app,
            store,
            debug,
            isHolding = { linkState.state.value.sleepHoldActive },
            ppg = ppgWindows,
            samsung = samsungWindows,
        )
    }

    val skinTemperature: SkinTemperatureRecorder by lazy { SkinTemperatureRecorder(store, skinSource, debug) }

    val spo2: Spo2Recorder by lazy {
        Spo2Recorder(app, store, spo2Source, debug, isHolding = { linkState.state.value.sleepHoldActive })
    }

    /**
     * Every recorder, in the order a plan is applied: the heart rate first,
     * because the step counter's variant follows the heart rate in force.
     */
    val recorders: List<Recorder> by lazy { listOf(heartRate, steps, sleepMinutes, ascent, nightHrv, skinTemperature, spo2) }

    val worn: WornMonitor by lazy {
        WornMonitor(
            app,
            debug,
            onWorn = { at, worn -> sleepMinutes.noteWorn(at, worn) },
            onHeartRateWanted = { wanted -> tellHost { onWornChanged(wanted) } },
        )
    }

    /** Its callback already runs on the main thread. */
    val workout: WorkoutRecorder by lazy { WorkoutRecorder(app, store, workoutState, debug) { host?.onWorkoutChanged() } }

    val ppgLogger: PpgRawLogger by lazy { PpgRawLogger(app) }

    /** When a phone last pulled, and the reminder to sync before unpulled rows are pruned. */
    val pulls: PhonePulls by lazy { PhonePulls(app) }
    val syncReminder: SyncReminder by lazy {
        SyncReminder(app, pulls, firstTrustedMillis = { linkState.state.value.trusted.minOfOrNull { it.trustedAtMillis } })
    }

    val link: WearLinkServerHost by lazy {
        WearLinkServerHost(
            adapter = { app.getSystemService(BluetoothManager::class.java)?.adapter },
            trust = trust,
            store = store,
            live = LiveHeartRate(app, isRecording = { heartRate.isRunning }),
            hrvAvailable = { nightHrv.hasSensor },
            skinAvailable = { skinTemperature.isAvailable },
            onPhoneActive = { at -> sleepMinutes.notePhoneActive(at) },
            heldPreferences = { WearPreferenceCodec.encode(WearSettings.read(app), abilities()) },
            setPreference = ::applyPhonePreference,
            workouts = ::workoutsPulled,
            localName = ::localName,
            state = linkState,
        )
    }

    private val workoutStore by lazy { WorkoutStore(MetricStoreWorkoutRows(store)) }

    /**
     * The workouts a phone pulls (`WO_SINCE`), the last series of its sync:
     * the pull is recorded here, for the status screen and the reminder to
     * sync. Link thread.
     */
    private fun workoutsPulled(sinceEpochMillis: Long, limit: Int): Page<WearLinkWorkout> =
        workoutStore.workoutsSince(sinceEpochMillis, limit).also { syncReminder.onPulled(System.currentTimeMillis()) }

    /** What the watch can offer the phone's settings screen, which the phone cannot change. */
    fun abilities() = WatchAbilities(
        samsung = samsungFeed.isAvailable,
        skinGranted = WearPermissions.hasSkinTemperature(app),
        spo2Granted = WearPermissions.hasOxygenSaturation(app),
    )

    /** A setting the phone changed: stored, and the running service follows. Link thread. */
    private fun applyPhonePreference(key: String, value: String): Boolean {
        val next = WearPreferenceCodec.apply(WearSettings.read(app), abilities(), key, value) ?: return false
        WearSettings.write(app, next)
        tellHost { onSettingsChanged() }
        return true
    }

    /** The steps stored for the minute that starts at [minuteStartMillis]. Any thread. */
    private fun stepsInMinute(minuteStartMillis: Long): Int =
        store.since(WearMetrics.STEPS, minuteStartMillis - 1, 2)
            .filter { it.epochMillis == minuteStartMillis }.sumOf { it.steps }

    /** The watch's Bluetooth name, as the phone will show it. */
    @SuppressLint("MissingPermission")
    private fun localName(): String {
        // The service only runs with the Bluetooth grant (startIfPermitted); the catch covers a revocation since.
        if (!WearPermissions.hasBluetooth(app)) return Build.MODEL
        return runCatching { app.getSystemService(BluetoothManager::class.java)?.adapter?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Build.MODEL
    }
}
