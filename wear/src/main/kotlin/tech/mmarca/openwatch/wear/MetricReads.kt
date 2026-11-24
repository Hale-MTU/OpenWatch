package tech.mmarca.openwatch.wear

import android.content.Context
import androidx.annotation.StringRes
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/*
 * What the tiles and complications read from the [MetricStore], one function
 * each, so the tiles and the complication of one metric always show the same
 * number: the heart rate and the steps on their own tiles, their
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
