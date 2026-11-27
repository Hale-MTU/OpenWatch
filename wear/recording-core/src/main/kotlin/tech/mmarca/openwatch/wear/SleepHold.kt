package tech.mmarca.openwatch.wear

/**
 * Whether the watch should hold the processor awake because the wearer
 * seems to be falling asleep, with no help from bedtime mode. Pure and
 * driven by the minutes the recorder closes, so it is testable without a
 * device. See [BedtimeHold] for why holding is needed at all.
 *
 * Start: the last [Config.quietMinutes] minutes were all worn, still (no
 * steps, no screen, little movement), and the heart rate fell: the mean of
 * the later half is at least [Config.belowLevelBpm] under the wearer's
 * recent level. The level is the median of the last [Config.levelReadings]
 * readings seen while not holding, so a long quiet sit at a steady pulse
 * never starts a hold. The fall is against that level, not inside the run:
 * the pulse of someone falling asleep sinks a few beats an hour, half a beat
 * in ten minutes (2026-11-27: 67 bpm at 23:30, 59 at 03:00, 54 at 05:00),
 * so a fall asked of the run itself is never seen. The later half needs a
 * reading, the run three, so a sparse day setting can still start it.
 *
 * Stop: [Config.wakeMinutesToRelease] waking minutes in a row (screen on,
 * steps, or movement of at least [Config.wakeMovement]), the watch off the
 * wrist or on the charger, or [Config.maxHoldMinutes]. After the cap the
 * hold waits [Config.cooldownMinutes] before it may start again. A turn in
 * bed is one or two waking minutes and does not stop it.
 *
 * Minutes may come again or late: the Samsung platform hands the heart
 * rate over minutes after the minute closed, so the recorder feeds a minute
 * a second time with its pulse, or for the first time once that pulse made
 * its row. Such a minute replaces or fills its place in the evidence, and
 * the start is judged again; only a minute newer than any before counts
 * toward a running hold.
 *
 * Against false starts: by day ([Minute.daytime]) the quiet run must be
 * [Config.quietMinutesByDay] long, since sitting still to read or watch a film
 * lowers the pulse too; and when the wearer uses the phone ([onPhoneActive],
 * an unlock the phone reports), a hold this class started ends at once and
 * none starts for [Config.phoneCooldownMinutes]. A glance at the watch is not
 * such a signal: its screen-on minute is one waking minute of the five.
 *
 * Costs battery: the processor stays awake for as long as the hold lasts,
 * up to the cap.
 */
class SleepHold(private val config: Config = Config()) {

    data class Config(
        val quietMinutes: Int = 10,
        val quietMinutesByDay: Int = 20,
        val phoneCooldownMinutes: Int = 30,
        val quietMovement: Float = 2f,
        val wakeMovement: Float = 8f,
        val belowLevelBpm: Float = 4f,
        val levelReadings: Int = 180,
        val minLevelReadings: Int = 20,
        val wakeMinutesToRelease: Int = 5,
        val maxHoldMinutes: Int = 10 * 60,
        val cooldownMinutes: Int = 60,
    )

    /** One closed minute: what the recorder knows about it. */
    data class Minute(
        val epochMillis: Long,
        val movement: Float,
        val bpm: Int?,
        val screenOn: Boolean,
        val steps: Int,
        val worn: Boolean,
        /** Between 06:00 and 22:00 local, when a longer quiet run is needed. */
        val daytime: Boolean = false,
    ) {
        val waking: Boolean get() = screenOn || steps > 0
    }

    /** The latest minutes by start, the newest [newest]; a late one fills its place. */
    private val recent = java.util.TreeMap<Long, Minute>()
    /** The heart rate of the minutes seen while not holding, by start. */
    private val levelSamples = java.util.TreeMap<Long, Int>()
    private var newest = Long.MIN_VALUE
    private var holding = false
    private var heldMinutes = 0
    private var wakeStreak = 0
    private var coolUntil = Long.MIN_VALUE

    /** True while the hold wants the processor awake. */
    val isHolding: Boolean get() = holding

    /**
     * Feeds one minute: a newly closed one, oldest first, or one again with
     * the heart rate that reached it late. Returns [isHolding] after it.
     */
    fun onMinute(minute: Minute): Boolean {
        val isNew = minute.epochMillis > newest
        if (!minute.worn) {
            if (isNew) {
                newest = minute.epochMillis
                reset()
            } else {
                recent.remove(minute.epochMillis)
            }
            return holding
        }
        if (isNew) newest = minute.epochMillis
        val window = maxOf(config.quietMinutes, config.quietMinutesByDay)
        if (minute.epochMillis <= newest - window * MINUTE_MILLIS) return holding
        recent[minute.epochMillis] = minute
        recent.headMap(newest - (window - 1) * MINUTE_MILLIS).clear()
        if (holding) {
            if (isNew) hold(minute)
        } else {
            minute.bpm?.let { levelSamples[minute.epochMillis] = it }
            while (levelSamples.size > config.levelReadings) levelSamples.pollFirstEntry()
            if (newest >= coolUntil && shouldStart()) {
                holding = true
                heldMinutes = 0
                wakeStreak = 0
            }
        }
        return holding
    }

    /**
     * The wearer used the phone at [epochMillis]: they are awake. A hold
     * ends, and none starts for [Config.phoneCooldownMinutes].
     */
    fun onPhoneActive(epochMillis: Long) {
        reset()
        coolUntil = maxOf(coolUntil, epochMillis + config.phoneCooldownMinutes * MINUTE_MILLIS)
    }

    /** The watch went on the charger or lost its wrist: nothing is held, and the evidence starts again. */
    fun onNotWorn() = reset()

    private fun reset() {
        holding = false
        recent.clear()
        wakeStreak = 0
    }

    private fun hold(minute: Minute) {
        heldMinutes++
        val awake = minute.waking || minute.movement >= config.wakeMovement
        wakeStreak = if (awake) wakeStreak + 1 else 0
        if (wakeStreak >= config.wakeMinutesToRelease) {
            holding = false
            recent.clear()
        } else if (heldMinutes >= config.maxHoldMinutes) {
            holding = false
            recent.clear()
            coolUntil = newest + config.cooldownMinutes * MINUTE_MILLIS
        }
    }

    private fun shouldStart(): Boolean {
        val need = if (recent[newest]?.daytime == true) config.quietMinutesByDay else config.quietMinutes
        // The run is the [need] minutes up to the newest, every one of them there.
        val run = (need - 1 downTo 0).map { recent[newest - it * MINUTE_MILLIS] ?: return false }
        if (run.any { it.waking || it.movement > config.quietMovement }) return false
        if (levelSamples.size < config.minLevelReadings) return false
        val half = run.size / 2
        val later = run.drop(half).mapNotNull { it.bpm }
        if (later.isEmpty() || run.count { it.bpm != null } < MIN_READINGS) return false
        val level = levelSamples.values.sorted()[levelSamples.size / 2]
        return later.average() <= level - config.belowLevelBpm
    }

    private companion object {
        const val MINUTE_MILLIS = 60_000L
        const val MIN_READINGS = 3
    }
}
