package tech.mmarca.openwatch.wear

import android.content.Context

/*
 * The three metric tiles (the Today and quick log tiles are in files of
 * their own). Each reads today's rows for its metric and says
 * why when it has nothing to show: a permission that is off, or a sensor
 * the watch lacks. The accents copy the phone app's `ui/theme/Color.kt`;
 * a change to that palette belongs here too.
 */

/**
 * The newest heart rate, the day's quarter-hour means, and its range. The
 * newest sample can be from before midnight, as long as it is under an hour
 * old ([TileHeartRate]); the curve and the range are today's only.
 */
class HeartRateTileService : MetricTileService() {

    /** `HeartColor`. */
    override val accent: Int = 0xFFD2497B.toInt()

    override val titleRes: Int = R.string.tile_heart_rate_label

    override fun read(day: TileDay, chart: TileChart, store: MetricStore): MetricTileContent {
        val now = readHeartRateNow(this, store, day.nowMillis)
        val samples = if (now != TileHeartRate.NotGranted) {
            store.since(WearMetrics.HEART_RATE, day.startMillis - 1, TileDay.MAX_HEART_RATE_SAMPLES_PER_DAY)
        } else {
            emptyList()
        }
        val figure = heartRateFigure(now)
        val title = getString(titleRes)
        val detail = day.range(samples)?.let { getString(R.string.tile_heart_rate_range, it.first, it.last) }
        return MetricTileContent(
            title = title,
            value = figure.value,
            caption = figure.caption,
            accent = accent,
            description = listOfNotNull("$title: ${figure.spoken}", detail).joinToString(", "),
            chart = chart.heartRate(day.meanPerBucket(samples), day),
            detail = detail,
        )
    }
}

/** A tile's figure: the value or a dash, the caption under it, and both as a screen reader says them. */
internal data class TileFigure(val value: String, val caption: String, val spoken: String)

/**
 * The heart rate now as the tiles print it: the bpm and how old it is, or a
 * dash and why there is none. Said as a sentence: the caption's unit belongs
 * with the number, and its dot is not read out well.
 */
internal fun Context.heartRateFigure(now: TileHeartRate): TileFigure = when (now) {
    TileHeartRate.NotGranted -> getString(R.string.tile_not_granted).let { TileFigure(TILE_NO_VALUE, it, it) }
    TileHeartRate.None -> getString(R.string.tile_heart_rate_none).let { TileFigure(TILE_NO_VALUE, it, it) }
    is TileHeartRate.Recent -> {
        val minutes = now.minutesAgo.toInt()
        if (minutes == 0) {
            TileFigure(now.bpm.toString(), getString(R.string.tile_heart_rate_caption_now), getString(R.string.tile_heart_rate_spoken_now, now.bpm))
        } else {
            TileFigure(
                value = now.bpm.toString(),
                caption = resources.getQuantityString(R.plurals.tile_heart_rate_caption_minutes, minutes, minutes),
                spoken = resources.getQuantityString(R.plurals.tile_heart_rate_spoken_minutes, minutes, now.bpm, minutes),
            )
        }
    }
}

/** The steps since midnight and their running total through the day. */
class StepsTileService : MetricTileService() {

    /** `StepsColor`. */
    override val accent: Int = 0xFF3F9A63.toInt()

    override val titleRes: Int = R.string.tile_steps_label

    override fun read(day: TileDay, chart: TileChart, store: MetricStore): MetricTileContent {
        val steps = when (val today = readStepsToday(this, store, day)) {
            is StepsToday.Missing -> return unavailable(getString(today.reason))
            is StepsToday.Counted -> today
        }
        val running = steps.running
        val title = getString(titleRes)
        val value = formatCount(steps.total)
        val caption = getString(R.string.tile_steps_caption)
        return MetricTileContent(title, value, caption, accent, "$title: $value $caption", chart.cumulative(running, day))
    }
}

/**
 * The floors climbed since midnight: the barometer's metres that enough
 * steps carry, over three metres a floor, as the phone counts them before it
 * writes them to Health Connect. Whole floors and whole metres, both rounded
 * down, as the phone's dashboard shows floors.
 */
class FloorsTileService : MetricTileService() {

    /** `FloorsColor`. */
    override val accent: Int = 0xFFA8881F.toInt()

    override val titleRes: Int = R.string.tile_floors_label

    override fun read(day: TileDay, chart: TileChart, store: MetricStore): MetricTileContent {
        val floors = when (val today = readFloorsToday(this, store, day)) {
            is FloorsToday.Missing -> return unavailable(getString(today.reason))
            is FloorsToday.Counted -> today
        }
        val running = floors.floors
        val title = getString(titleRes)
        val value = formatCount(floors.total)
        val caption = getString(R.string.tile_floors_caption)
        val detail = getString(R.string.tile_floors_climbed, formatCount(floors.metresTotal))
        return MetricTileContent(
            title = title,
            value = value,
            caption = caption,
            accent = accent,
            description = "$title: $value $caption, $detail",
            chart = chart.cumulative(running, day),
            detail = detail,
        )
    }
}
