package tech.mmarca.openwatch.wear

import kotlin.math.sqrt

/**
 * Whether the arm stayed still through a spot HRV measurement, judged from
 * the accelerometer's magnitude in blocks of [BLOCK_MILLIS]. A block moves
 * when the spread of its magnitude passes [MAX_SPREAD] m/s²; the window is
 * spoiled when more than [MAX_MOVING_SHARE] of its blocks move. Both numbers
 * are judgement, not fitted: a resting wrist reads well under 0.1 m/s²,
 * typing or a wave of the hand well over 0.5. The night windows judge
 * stillness from the minute rows instead (`NightHrvSchedule`). Pure.
 */
object Stillness {

    const val BLOCK_MILLIS = 5_000L
    const val MAX_SPREAD = 0.3
    const val MAX_MOVING_SHARE = 0.2

    /** A block with fewer readings says little about its spread and is not judged. */
    private const val MIN_READINGS = 10

    /**
     * True when the window moved too much. [timesMillis] are ascending and
     * [magnitudes] the readings at them, in m/s². With nothing it can judge
     * it counts as moved: a measurement that cannot show the arm was still is
     * not kept.
     */
    fun moved(timesMillis: LongArray, magnitudes: DoubleArray): Boolean {
        if (timesMillis.isEmpty() || timesMillis.size != magnitudes.size) return true
        val start = timesMillis.first()
        val judged = magnitudes.indices.groupBy { (timesMillis[it] - start) / BLOCK_MILLIS }.values.filter { it.size >= MIN_READINGS }
        if (judged.isEmpty()) return true
        val moving = judged.count { block -> spread(block.map { magnitudes[it] }) > MAX_SPREAD }
        return moving > MAX_MOVING_SHARE * judged.size
    }

    /** The standard deviation of [values], m/s²: a block's spread. A workout's rest is judged by it too (`RestDetector`). */
    fun spread(values: List<Double>): Double {
        val mean = values.average()
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
    }
}
