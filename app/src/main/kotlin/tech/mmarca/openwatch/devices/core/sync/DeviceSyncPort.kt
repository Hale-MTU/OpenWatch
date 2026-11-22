package tech.mmarca.openwatch.devices.core.sync

import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/** How far a sync has got. Device-agnostic. */
enum class DeviceSyncPhase { HANDSHAKE, LISTING, DOWNLOADING, COMPLETE, FAILED }

/** A progress tick: the [phase] and, while downloading, [filesDone] of [filesTotal]. */
data class DeviceSyncProgress(
    val phase: DeviceSyncPhase,
    val filesTotal: Int = 0,
    val filesDone: Int = 0,
)

/** A series a sync left out for want of a Health Connect grant, so the screen can say which. */
enum class UnwrittenSeries { STEPS, WALKING_ENERGY, RESTING_HEART_RATE, HEART_RATE_VARIABILITY, RESPIRATORY_RATE, OXYGEN_SATURATION, SKIN_TEMPERATURE, WORKOUTS }

/** Who asked for a sync. An integration may act on it; nothing else about the run differs. */
enum class SyncTrigger {
    /**
     * Someone waits on it: a tap on a screen, or another app over OpenLink. A failure stays on
     * the screen, and an integration may hold the link a little longer for the device's own errands.
     */
    REQUESTED,

    /** The schedule, or a device that announced new data. A failure ends idle, with no banner. */
    BACKGROUND,
}

/** The outcome of a whole sync-and-persist run. */
sealed class DeviceSyncResult {

    /**
     * The sync finished with [fileCount] files, or samples for an
     * integration that counts those, [nightCount] estimated nights of sleep
     * and [stepMinuteCount] minutes of steps written. 0 is a success.
     * [missingBodyWeight] says an estimate that needs the body weight was
     * skipped because none is known, and [weightReadWithheld] that Health
     * Connect's weight was out of reach for want of the read grant. [unwritten] are the series the run left
     * out for want of a Health Connect grant, and [withheldPermissions] the
     * grants (a write, or a read the write depends on) the screen asks for.
     */
    data class Succeeded(
        val fileCount: Int,
        val nightCount: Int = 0,
        val stepMinuteCount: Int = 0,
        val missingBodyWeight: Boolean = false,
        val weightReadWithheld: Boolean = false,
        val unwritten: Set<UnwrittenSeries> = emptySet(),
        val withheldPermissions: Set<String> = emptySet(),
        /** The run stopped at its [SyncBudget] with more on the device; a continuation should follow. */
        val moreRemains: Boolean = false,
    ) : DeviceSyncResult()

    /**
     * The sync failed. [error] is what the screen shows; an integration with
     * a sentence already in hand passes it as a [ScreenError.Message], one
     * with a typed cause passes a [ScreenError.Text] or [ScreenError.PermissionDenied].
     */
    data class Failed(val error: ScreenError) : DeviceSyncResult() {
        constructor(message: String) : this(ScreenError.Message(message))

        /** The rendered sentence, for a message-shaped error; the resource id's name otherwise. */
        val message: String
            get() = (error as? ScreenError.Message)?.text ?: error.toString()
    }
}

/**
 * The seam between generic sync orchestration and one integration. Owns
 * the whole operation: pull, import, persist, stamp. A port: only DI knows
 * which integration satisfies it.
 */
interface DeviceSyncPort {

    /** Whether this integration owns the sync-and-persist for [device]. */
    fun canSync(device: BleSensorDevice): Boolean

    /** Runs the whole sequence for [device], asked for by [trigger]. Never throws. */
    suspend fun sync(
        device: BleSensorDevice,
        trigger: SyncTrigger = SyncTrigger.REQUESTED,
        onProgress: ((DeviceSyncProgress) -> Unit)? = null,
    ): DeviceSyncResult
}
