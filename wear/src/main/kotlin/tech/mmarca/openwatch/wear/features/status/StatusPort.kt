package tech.mmarca.openwatch.wear.features.status

import android.content.Context
import android.database.sqlite.SQLiteException
import tech.mmarca.openwatch.wear.PendingPhone
import tech.mmarca.openwatch.wear.SamsungDiagnostics
import tech.mmarca.openwatch.wear.StepRecorder
import tech.mmarca.openwatch.wear.SyncRetention
import tech.mmarca.openwatch.wear.WearAppService
import tech.mmarca.openwatch.wear.WearGraph
import tech.mmarca.openwatch.wear.WearMetrics
import tech.mmarca.openwatch.wear.WearPermissions
import tech.mmarca.openwatch.wear.samsung.SamsungHealthPlatform

/**
 * What the status screen reads and changes, apart from the screen: the
 * store, the grants and the trust store on the watch ([GraphStatusPort]), a
 * fake in a test. Every call blocks; the ViewModel makes them off the main
 * thread.
 */
internal interface StatusPort {
    /** The store's counts, the grants and the system settings now; null when the store cannot be read. */
    fun read(): StatusReadings?

    fun allow(phone: PendingPhone)

    fun block(phone: PendingPhone)

    /** Starts or stops the raw PPG log of a debuggable build. */
    fun togglePpgLog()
}

/** [StatusPort] over the process's [WearGraph]. */
internal class GraphStatusPort(private val context: Context, private val graph: WearGraph) : StatusPort {

    // What the watch has does not change while the process lives.
    private val hasStepCounter by lazy { StepRecorder.hasStepCounter(context) }
    private val samsungPlatform by lazy { SamsungHealthPlatform.isInstalled(context) }

    override fun read(): StatusReadings? {
        val store = graph.store
        val now = System.currentTimeMillis()
        val lastPulled = graph.pulls.lastPulledMillis
        val firstTrusted = graph.linkState.state.value.trusted.minOfOrNull { it.trustedAtMillis }
        return try {
            StatusReadings(
                heartRateGranted = WearPermissions.hasHeartRate(context),
                bluetoothGranted = WearPermissions.hasBluetooth(context),
                stepsGranted = WearPermissions.hasActivityRecognition(context),
                hasStepCounter = hasStepCounter,
                latestBpm = store.latest(WearMetrics.HEART_RATE)?.bpm,
                heartRateSamples = store.count(WearMetrics.HEART_RATE),
                sleepMinutes = store.count(WearMetrics.SLEEP_MINUTES),
                stepMinutes = store.count(WearMetrics.STEPS),
                samsungDiagnostics = SamsungDiagnostics.consentOn(context.contentResolver),
                samsungPlatform = samsungPlatform,
                ppgLogAvailable = graph.ppgLogger.isAvailable,
                readAtMillis = now,
                lastPulledMillis = lastPulled,
                syncWarning = SyncRetention.warning(now, lastPulled, firstTrusted),
            )
        } catch (_: SQLiteException) {
            // A locked or corrupt file: the screen keeps what it showed, and the next refresh tries again.
            null
        }
    }


    override fun allow(phone: PendingPhone) {
        graph.trust.trust(phone.address, phone.token, phone.name)
        graph.phoneRequests.cancel()
    }

    override fun block(phone: PendingPhone) {
        graph.trust.block(phone.address)
        graph.phoneRequests.cancel()
    }

    override fun togglePpgLog() = WearAppService.togglePpgLog(context)
}
