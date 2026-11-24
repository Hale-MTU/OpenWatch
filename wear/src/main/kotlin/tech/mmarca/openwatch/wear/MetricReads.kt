package tech.mmarca.openwatch.wear

import android.content.Context
import androidx.annotation.StringRes
import tech.mmarca.healthcalculations.insights.AscentCredit
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/*
 * What the tiles and complications read from the [MetricStore], one function
 * each, so the tiles and the complication of one metric always show the same
 * number: the heart rate, the steps and the floors on their own tiles, their
 * complications and the Today tile, the water on the quick log tile. Each
 * blocks on the store: call it off the main thread. Each says why when it
 * has nothing: a permission that is off, or a sensor the watch lacks.
 */

/** The newest heart rate and how old it is, or why there is none. */
internal fun readHeartRateNow(context: Context, store: MetricStore, nowMillis: Long): TileHeartRate =
    TileHeartRate.of(WearPermissions.hasHeartRate(context), store.latest(WearMetrics.HEART_RATE), nowMillis)

/** Today's steps on this watch, or why there are none. */
internal sealed interface StepsToday {
    /** No step counter, or no grant: [reason] is the caption that says so. */
    data class Missing(@param:StringRes val reason: Int) : StepsToday

    /** The running total at the end of each quarter hour so far ([TileDay.cumulative]), now last. */
    data class Counted(val running: List<Double>) : StepsToday {
        val total: Long get() = running.last().toLong()
    }
}

internal fun readStepsToday(context: Context, store: MetricStore, day: TileDay): StepsToday {
    val missing = when {
        !StepRecorder.hasStepCounter(context) -> R.string.tile_steps_no_sensor
        !WearPermissions.hasActivityRecognition(context) -> R.string.tile_not_granted
        else -> null
    }
    if (missing != null) return StepsToday.Missing(missing)
    val minutes = store.since(WearMetrics.STEPS, day.startMillis - 1, TileDay.MAX_MINUTES_PER_DAY)
    return StepsToday.Counted(day.cumulative(TileDay.stepAmounts(minutes)))
}

/** Today's floors on this watch, or why there are none. */
internal sealed interface FloorsToday {
    /** No barometer or step counter, or no grant: [reason] is the caption that says so. */
    data class Missing(@param:StringRes val reason: Int) : FloorsToday

    /** The metres climbed that count, as a running total at the end of each quarter hour so far ([TileDay.cumulative]), now last. */
    data class Counted(val metres: List<Double>) : FloorsToday {
        /** The same running total in floors of [AscentCredit.METRES_PER_FLOOR]. */
        val floors: List<Double> get() = metres.map { it / AscentCredit.METRES_PER_FLOOR }

        /** Whole floors, rounded down, as the phone's dashboard shows them. */
        val total: Long get() = TileDay.wholeDown(floors.last())

        /** Whole metres, rounded down. */
        val metresTotal: Long get() = TileDay.wholeDown(metres.last())
    }
}

/**
 * The floors climbed since midnight: the barometer's metres that enough
 * steps carry ([TileDay.creditedAscent]), as the phone counts them before it
 * writes them to Health Connect. Height counts only with steps around it, so
 * floors need the step counter too.
 */
internal fun readFloorsToday(context: Context, store: MetricStore, day: TileDay): FloorsToday {
    val missing = when {
        !AscentRecorder.hasBarometer(context) -> R.string.tile_floors_no_sensor
        !StepRecorder.hasStepCounter(context) -> R.string.tile_steps_no_sensor
        !WearPermissions.hasActivityRecognition(context) -> R.string.tile_not_granted
        else -> null
    }
    if (missing != null) return FloorsToday.Missing(missing)
    val ascent = store.since(WearMetrics.ASCENT, day.startMillis - 1, TileDay.MAX_MINUTES_PER_DAY)
    // The minute before midnight carries the first minute's credit.
    val steps = store.since(WearMetrics.STEPS, day.startMillis - TileDay.MINUTE_MILLIS - 1, TileDay.MAX_MINUTES_PER_DAY + 1)
    return FloorsToday.Counted(day.cumulative(TileDay.creditedAscent(ascent, steps)))
}

/**
 * The water logged on the watch today, millilitres: every glass from local
 * midnight to the next, as the dashboard's water tile adds them up.
 */
internal fun readWaterToday(store: MetricStore, day: TileDay): Double =
    store.since(WearMetrics.ENTRIES, day.startMillis - 1, MAX_ENTRIES_PER_DAY, day.endMillis)
        .filter { it.type == WearLinkEntry.Type.WATER }
        .sumOf { it.value }

/** Far above what a wearer logs by hand in a day. */
private const val MAX_ENTRIES_PER_DAY = 10_000
