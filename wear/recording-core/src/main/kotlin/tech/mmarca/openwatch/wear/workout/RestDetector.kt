package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wear.Stillness

/**
 * Finds the moment the arm came to rest in a running workout, from the
 * accelerometer's magnitude in blocks of [Stillness.BLOCK_MILLIS]. A block
 * rests when its spread stays under [Stillness.MAX_SPREAD]; a block with too
 * few readings, or a gap between blocks, counts as movement, so a starved
 * sensor never reports a rest. Only the latest unbroken run of resting blocks
 * counts: a stop at a crossing in the middle of a run is not the end of the
 * exercise.
 *
 * A rest still counts for [REACH_MILLIS] after it ended: to stop the workout
 * the wearer raises the wrist, swipes to the controls and taps twice, and
 * that movement must not undo the minute of rest before it. Pure.
 */
class RestDetector {

    private var blockIndex = Long.MIN_VALUE
    private val block = ArrayList<Double>()

    /** When the current run of resting blocks began; null while the arm moves. */
    private var restingSince: Long? = null
    private var lastClosedEnd = Long.MIN_VALUE

    /** The newest run of resting blocks that lasted [MIN_REST_MILLIS] and has ended: its start and its end. */
    private var lastRest: LongRange? = null

    fun onAcceleration(epochMillis: Long, magnitude: Double) {
        val index = epochMillis / Stillness.BLOCK_MILLIS
        if (index != blockIndex) {
            close()
            blockIndex = index
        }
        block += magnitude
    }

    /**
     * When the rest began: the current one if it has lasted [MIN_REST_MILLIS]
     * by the last closed block, else one that did and ended at most
     * [REACH_MILLIS] before [now]. Null otherwise, and while the sensor has
     * delivered nothing for two blocks.
     */
    fun restStartMillis(now: Long): Long? {
        if (now - lastClosedEnd > 2 * Stillness.BLOCK_MILLIS) return null
        val since = restingSince
        if (since != null && lastClosedEnd - since >= MIN_REST_MILLIS) return since
        val rest = lastRest ?: return null
        return rest.first.takeIf { now - rest.last <= REACH_MILLIS }
    }

    private fun close() {
        if (block.isEmpty()) return
        val start = blockIndex * Stillness.BLOCK_MILLIS
        val adjacent = lastClosedEnd == start
        val rests = block.size >= MIN_READINGS && Stillness.spread(block) <= Stillness.MAX_SPREAD
        block.clear()
        val run = restingSince
        // A run of rest ends at a moving block or a gap: kept, if it was long enough, for the reach that follows.
        if (run != null && (!rests || !adjacent) && lastClosedEnd - run >= MIN_REST_MILLIS) lastRest = run..lastClosedEnd
        restingSince = when {
            !rests -> null
            adjacent && run != null -> run
            else -> start
        }
        lastClosedEnd = start + Stillness.BLOCK_MILLIS
    }

    companion object {
        /** A minute of rest: the first of Cole et al.'s readings needs it. */
        const val MIN_REST_MILLIS = 60_000L

        /** Raising the wrist and stopping the workout: three blocks. */
        const val REACH_MILLIS = 15_000L

        private const val MIN_READINGS = 10
    }
}
