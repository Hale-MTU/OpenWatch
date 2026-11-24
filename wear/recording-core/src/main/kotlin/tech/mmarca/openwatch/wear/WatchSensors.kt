package tech.mmarca.openwatch.wear

/**
 * What this watch has to measure with, read once from its sensor list and
 * its features: the facts the screens go by to offer a metric, a workout's
 * field or a spot measurement only where the watch can give it. Plain kinds,
 * so the decisions are tested off the watch; `WearGraph.sensors` reads them.
 */
data class WatchSensors(
    /** The heart rate sensor (`TYPE_HEART_RATE`). */
    val heartRate: Boolean,
    /** Single beats, each at its time (`TYPE_HEART_BEAT`): what a spot HRV rests on. */
    val heartBeat: Boolean,
    /** The step counter (`TYPE_STEP_COUNTER`). */
    val stepCounter: Boolean,
    /** The barometer (`TYPE_PRESSURE`): the height climbed, and the air pressure itself. */
    val barometer: Boolean,
    /** A GPS receiver (`FEATURE_LOCATION_GPS`): a workout's route, distance and pace. */
    val gps: Boolean,
) {
    /** The spot measurements a wearer can start on this watch. */
    val spotKinds: Set<SpotKind>
        get() = buildSet {
            if (heartRate) add(SpotKind.HEART_RATE)
            if (heartBeat) add(SpotKind.HRV)
            if (barometer) add(SpotKind.AIR_PRESSURE)
        }

    companion object {
        /** A watch with every sensor, for a test or a preview. */
        val ALL = WatchSensors(heartRate = true, heartBeat = true, stepCounter = true, barometer = true, gps = true)

        /** A watch with none. */
        val NONE = WatchSensors(heartRate = false, heartBeat = false, stepCounter = false, barometer = false, gps = false)
    }
}

/**
 * A reading the wearer starts by hand, by its plain kind; the screens give
 * each its name, icon and colour, and `SpotMeasurements` takes it
 * ([SpotRules]). The names are in the measurement's route, so they are
 * never renamed.
 */
enum class SpotKind(
    /** The longest the measurement runs, seconds. */
    val seconds: Int,
) {
    /** The pulse now, from the heart rate sensor: settled once enough readings agree. */
    HEART_RATE(30),

    /** The RMSSD of a minute of single beats; it always runs the whole minute. */
    HRV(60),

    /** The barometer, with the altitude it stands for at standard pressure. */
    AIR_PRESSURE(10),
}
