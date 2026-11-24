package tech.mmarca.openwatch.wear.workout

import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * Pauses a running workout when the wearer stops, and goes on when they
 * move again, for the sports where a stop at a crossing or a bench is part
 * of the session: running and cycling with GPS. It reads the speed of the
 * GPS fixes, which arrive whether the workout is paused or not, and nothing
 * else.
 *
 * A stop is [PAUSE_AFTER_MILLIS] of fixes under the sport's stop speed; the
 * pause begins at the last moment of movement, so the wait is not counted.
 * Going on needs [RESUME_FIXES] fixes in a row over a higher speed, so the
 * jitter of a standing wearer does not restart it. No fix at all (a tunnel,
 * a lost signal) is no evidence of a stop. Fixes with a poor accuracy are
 * ignored. Pure.
 */
class AutoPause(sport: WorkoutSport) {

    enum class Verdict { PAUSE, RESUME }

    private val stopSpeed = if (sport == WorkoutSport.BIKING) BIKE_STOP_SPEED else FOOT_STOP_SPEED
    private val resumeSpeed = stopSpeed * RESUME_FACTOR

    private var lastMovingAt: Long? = null
    private var lastFixAt = Long.MIN_VALUE
    private var lastFix: WorkoutFix? = null
    private var fastFixes = 0

    /** One GPS fix; its reported speed counts, or the speed from the fix before when it has none. */
    fun onFix(fix: WorkoutFix) {
        val accuracy = fix.accuracyMetres
        if (accuracy != null && accuracy > WorkoutTracker.MAX_ACCURACY_METRES) return
        val before = lastFix
        lastFix = fix
        val speed = fix.speedMetresPerSecond ?: before?.let { derived(it, fix) } ?: return
        lastFixAt = fix.epochMillis
        if (speed >= stopSpeed) lastMovingAt = fix.epochMillis else if (lastMovingAt == null) lastMovingAt = fix.epochMillis
        fastFixes = if (speed >= resumeSpeed) fastFixes + 1 else 0
    }

    /** What to do at [now], given whether this class holds the workout paused ([pausedByThis]); null for nothing. */
    fun verdict(now: Long, pausedByThis: Boolean): Verdict? {
        val fresh = now - lastFixAt <= FIX_FRESH_MILLIS
        if (pausedByThis) return Verdict.RESUME.takeIf { fresh && fastFixes >= RESUME_FIXES }
        val moving = lastMovingAt ?: return null
        return Verdict.PAUSE.takeIf { fresh && now - moving >= PAUSE_AFTER_MILLIS }
    }

    /** When the movement was last seen: where a pause should begin. */
    val pauseAt: Long? get() = lastMovingAt

    /** A new run of movement counts from [now]; called when the workout goes on. */
    fun restart(now: Long) {
        lastMovingAt = now
        fastFixes = 0
    }

    private fun derived(from: WorkoutFix, to: WorkoutFix): Double? {
        val seconds = (to.epochMillis - from.epochMillis) / MILLIS_PER_SECOND
        if (seconds <= 0.0) return null
        return WorkoutTracker.distanceBetween(from, to) / seconds
    }

    companion object {
        /** On foot, below this the wearer is standing: slower than anyone walks. m/s. */
        const val FOOT_STOP_SPEED = 0.5

        /** On a bike, below this the rider is putting a foot down. m/s. */
        const val BIKE_STOP_SPEED = 1.0

        /** Going on takes a speed this much above the stop speed. */
        const val RESUME_FACTOR = 1.6
        const val RESUME_FIXES = 3
        const val PAUSE_AFTER_MILLIS = 8_000L

        /** A verdict needs a fix this recent: the GPS reports once a second. */
        const val FIX_FRESH_MILLIS = 3_000L

        private const val MILLIS_PER_SECOND = 1000.0

        /** The sports that pause by themselves; they need the GPS. */
        fun appliesTo(sport: WorkoutSport): Boolean = sport == WorkoutSport.RUNNING || sport == WorkoutSport.BIKING
    }
}
