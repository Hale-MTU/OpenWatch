package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wear.features.measure.Stillness

/**
 * Finds the moment the arm came to rest in a running workout, from the accelerometer's magnitude in
 * blocks of [Stillness.BLOCK_MILLIS]. A block rests when its spread stays under [Stillness.MAX_SPREAD];
 * a block with too few readings, or a gap between blocks, counts as movement, so a starved sensor
 * never reports a rest. Only the latest unbroken run of resting blocks counts: a stop at a crossing
 * in the middle of a run is not the end of the exercise.
 */
internal class RestDetector {

    private var blockIndex = Long.MIN_VALUE
    private val block = ArrayList<Double>()
    private var restingSince: Long? = null
    private var lastClosedEnd = Long.MIN_VALUE

    fun onAcceleration(epochMillis: Long, magnitude: Double) {
        val index = epochMillis / Stillness.BLOCK_MILLIS
        if (index != blockIndex) {
            close()
            blockIndex = index
        }
        block += magnitude
    }

    /**
     * When the current rest began, if it has lasted [MIN_REST_MILLIS] by the last closed block and the
     * sensor is still delivering at [now]; null otherwise.
     */
    fun restStartMillis(now: Long): Long? {
        val since = restingSince ?: return null
        if (now - lastClosedEnd > 2 * Stillness.BLOCK_MILLIS) return null
        return since.takeIf { lastClosedEnd - it >= MIN_REST_MILLIS }
    }

    private fun close() {
        if (block.isEmpty()) return
        val start = blockIndex * Stillness.BLOCK_MILLIS
        val adjacent = lastClosedEnd == start
        val rests = block.size >= MIN_READINGS && Stillness.spread(block) <= Stillness.MAX_SPREAD
        block.clear()
        restingSince = when {
            !rests -> null
            adjacent && restingSince != null -> restingSince
            else -> start
        }
        lastClosedEnd = start + Stillness.BLOCK_MILLIS
    }

    companion object {
        /** A minute of rest: the first of Cole et al.'s readings needs it. */
        const val MIN_REST_MILLIS = 60_000L
        private const val MIN_READINGS = 10
    }
}
