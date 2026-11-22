package tech.mmarca.openwatch.devices.core.sync

import tech.mmarca.openwatch.domain.model.BleSensorDevice

/** How far a sync has got. Device-agnostic. */
enum class DeviceSyncPhase { HANDSHAKE, LISTING, DOWNLOADING }

/** What a progress tick counts. */
enum class ProgressUnit { FILES, PAGES }

/** A progress tick: the [phase] and, while downloading, [done] of [total] [unit]s. */
data class DeviceSyncProgress(
    val phase: DeviceSyncPhase,
    val total: Int = 0,
    val done: Int = 0,
    val unit: ProgressUnit = ProgressUnit.FILES,
)

/** What a run counts as written, for the screen to say. */
enum class SyncTally {
    /** Files downloaded and handed to the importer, such as a Garmin watch's FIT files. */
    FILES,
    HEART_RATE_SAMPLES,
    STEP_MINUTES,

    /** Nights of sleep estimated. */
    SLEEP_NIGHTS,
}

/** A series a run can leave out, each with a sentence of its own on the screen. */
enum class HealthSeries {
    STEPS,
    WALKING_ENERGY,
    WORKOUT_ENERGY,
    RESTING_HEART_RATE,
    HEART_RATE_VARIABILITY,
    RESPIRATORY_RATE,
    OXYGEN_SATURATION,
    SKIN_TEMPERATURE,
    WORKOUTS,

    /** Records of several types at once, which the screen names by their grants. */
    RECORDS,
}

/** Why a run left a series out. */
enum class SkipReason {
    /** A Health Connect grant is missing. */
    PERMISSION,

    /** An estimate needs the body weight and none is known; a read grant may be what is missing. */
    NO_BODY_WEIGHT,
}

/** A series a run left out, why, and the grants that would let the next run write it. */
data class SkippedSeries(
    val series: HealthSeries,
    val reason: SkipReason,
    val permissions: Set<String> = emptySet(),
)

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
     * The run finished. [written] counts what it wrote, by kind; nothing is a success too.
     * [skipped] is what it left out and why.
     */
    data class Succeeded(
        val written: Map<SyncTally, Int> = emptyMap(),
        val skipped: Set<SkippedSeries> = emptySet(),
        /** The run stopped at its [SyncBudget] with more on the device; a continuation should follow. */
        val moreRemains: Boolean = false,
    ) : DeviceSyncResult() {
        /** The grants the skipped series wait for: what the screen's one button asks for. */
        val withheldPermissions: Set<String> get() = skipped.flatMapTo(mutableSetOf()) { it.permissions }
    }

    /** The run failed, for [failure], which the screen words; [partial] is what it wrote first. */
    data class Failed(val failure: SyncFailure, val partial: Succeeded? = null) : DeviceSyncResult()
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
