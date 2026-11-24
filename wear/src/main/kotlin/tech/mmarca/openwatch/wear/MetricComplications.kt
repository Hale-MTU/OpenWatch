package tech.mmarca.openwatch.wear

import android.app.PendingIntent
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeRange
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.mmarca.healthcalculations.insights.MetricDailyGoalKey

/**
 * One metric as a watch face complication: a short text with the metric's
 * icon, its value and its unit, and for the steps also a ranged value, the
 * day's steps against a goal. It reads what the metric's tile reads
 * ([readHeartRateNow], [readStepsToday], [readFloorsToday]) from the
 * process's one [MetricStore], on the IO dispatcher, and starts no sensor:
 * the recorders in [WearAppService] write whether a complication is shown
 * or not. The system asks again about every five minutes (the manifest's
 * update period); a day's count holds until midnight, so yesterday's never
 * shows into the new day, and a tap opens [MainActivity].
 */
abstract class MetricComplicationService : SuspendingComplicationDataSourceService() {

    /**
     * The value as the slot shows it, as a screen reader says it, as a number for a ranged
     * value, and until when it holds (epoch milliseconds; null for until the next request):
     * a day's count until midnight, a heart rate until it is too old to show.
     */
    internal class Reading(val value: String, val spoken: String, val amount: Double? = null, val validUntilMillis: Long? = null)

    internal abstract val iconRes: Int

    /** The unit, the short text's title. */
    internal abstract val unitRes: Int

    /** The top of a ranged value, where the complication has one: it then answers [ComplicationType.RANGED_VALUE] too. */
    internal open val rangeMax: Double? = null

    /** The reading now from [store]; blocks on the store. */
    internal abstract fun read(store: MetricStore, nowMillis: Long): Reading

    /** What the watch face editor shows before the first request. */
    internal abstract fun preview(): Reading

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val type = request.complicationType
        if (!answers(type)) return null
        val reading = withContext(Dispatchers.IO) {
            try {
                read(wearGraph.store, System.currentTimeMillis())
            } catch (_: SQLiteException) {
                // A locked or corrupt file: a dash rather than the system's empty slot; the next request tries again.
                Reading(TILE_NO_VALUE, getString(R.string.tile_read_failed))
            }
        }
        return data(reading, type)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? = if (answers(type)) data(preview(), type) else null

    internal fun formatCount(count: Long): String = NumberFormat.getIntegerInstance().format(count)

    private fun answers(type: ComplicationType): Boolean =
        type == ComplicationType.SHORT_TEXT || (type == ComplicationType.RANGED_VALUE && rangeMax != null)

    private fun data(reading: Reading, type: ComplicationType): ComplicationData {
        val max = rangeMax
        return if (type == ComplicationType.RANGED_VALUE && max != null) rangedValue(reading, max) else shortText(reading)
    }

    private fun shortText(reading: Reading): ComplicationData =
        ShortTextComplicationData.Builder(text(reading.value), text(reading.spoken))
            .setTitle(text(getString(unitRes)))
            .setMonochromaticImage(icon())
            .setTapAction(openApp())
            .apply { reading.validUntilMillis?.let { setValidTimeRange(TimeRange.before(Instant.ofEpochMilli(it))) } }
            .build()

    /** The value against [max]; a reading without a number (a dash and why) stands at the start. */
    private fun rangedValue(reading: Reading, max: Double): ComplicationData =
        RangedValueComplicationData.Builder(
            value = (reading.amount ?: 0.0).coerceIn(0.0, max).toFloat(),
            min = 0f,
            max = max.toFloat(),
            contentDescription = text(reading.spoken),
        )
            .setText(text(reading.value))
            .setTitle(text(getString(unitRes)))
            .setMonochromaticImage(icon())
            .setTapAction(openApp())
            .apply { reading.validUntilMillis?.let { setValidTimeRange(TimeRange.before(Instant.ofEpochMilli(it))) } }
            .build()

    private fun icon(): MonochromaticImage = MonochromaticImage.Builder(Icon.createWithResource(this, iconRes)).build()

    private fun text(value: String) = PlainComplicationText.Builder(value).build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        // One request code per complication, apart from the notifications' 0, 4 and 5.
        javaClass.name.hashCode(),
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The newest heart rate, as the heart rate tile has it: a dash when it is older than an hour or not granted. */
class HeartRateComplicationService : MetricComplicationService() {

    override val iconRes: Int = R.drawable.ic_metric_heart_rate

    override val unitRes: Int = R.string.unit_bpm

    override fun read(store: MetricStore, nowMillis: Long): Reading = when (val now = readHeartRateNow(this, store, nowMillis)) {
        TileHeartRate.NotGranted -> Reading(TILE_NO_VALUE, getString(R.string.tile_not_granted))
        TileHeartRate.None -> Reading(TILE_NO_VALUE, getString(R.string.tile_heart_rate_none))
        // Shown until it is an hour old, when the tile shows a dash; the minute's start is near enough.
        is TileHeartRate.Recent -> Reading(
            now.bpm.toString(),
            spoken(now),
            validUntilMillis = nowMillis - now.minutesAgo * TileDay.MINUTE_MILLIS + TileHeartRate.STALE_AFTER_MILLIS,
        )
    }

    override fun preview(): Reading = Reading(PREVIEW_BPM.toString(), getString(R.string.tile_heart_rate_spoken_now, PREVIEW_BPM))

    private fun spoken(now: TileHeartRate.Recent): String =
        if (now.minutesAgo == 0L) {
            getString(R.string.tile_heart_rate_spoken_now, now.bpm)
        } else {
            resources.getQuantityString(R.plurals.tile_heart_rate_spoken_minutes, now.minutesAgo.toInt(), now.bpm, now.minutesAgo.toInt())
        }

    private companion object {
        const val PREVIEW_BPM = 64
    }
}

/**
 * The steps since midnight on this watch, as the steps tile counts them: a
 * short text, or a ranged value against a day's goal of 8,000 steps,
 * health-calculations' default step goal ([MetricDailyGoalKey.STEPS]), the
 * phone app's own first goal; the watch has no goal setting.
 */
class StepsComplicationService : MetricComplicationService() {

    override val iconRes: Int = R.drawable.ic_metric_steps

    override val unitRes: Int = R.string.unit_steps

    override val rangeMax: Double = MetricDailyGoalKey.STEPS.defaultValue

    override fun read(store: MetricStore, nowMillis: Long): Reading =
        TileDay.of(nowMillis, ZoneId.systemDefault()).let { day ->
            when (val today = readStepsToday(this, store, day)) {
                is StepsToday.Missing -> Reading(TILE_NO_VALUE, getString(today.reason))
                is StepsToday.Counted -> formatCount(today.total).let { Reading(it, spoken(it), today.total.toDouble(), day.endMillis) }
            }
        }

    override fun preview(): Reading = formatCount(PREVIEW_STEPS).let { Reading(it, spoken(it), PREVIEW_STEPS.toDouble()) }

    private fun spoken(value: String): String = "${getString(R.string.tile_steps_label)}: $value ${getString(R.string.tile_steps_caption)}"

    private companion object {
        const val PREVIEW_STEPS = 6_480L
    }
}

/** The floors climbed since midnight, as the floors tile counts them: whole floors, rounded down. */
class FloorsComplicationService : MetricComplicationService() {

    override val iconRes: Int = R.drawable.ic_metric_floors

    override val unitRes: Int = R.string.unit_floors

    override fun read(store: MetricStore, nowMillis: Long): Reading =
        TileDay.of(nowMillis, ZoneId.systemDefault()).let { day ->
            when (val today = readFloorsToday(this, store, day)) {
                is FloorsToday.Missing -> Reading(TILE_NO_VALUE, getString(today.reason))
                is FloorsToday.Counted -> formatCount(today.total).let { Reading(it, spoken(it), validUntilMillis = day.endMillis) }
            }
        }

    override fun preview(): Reading = formatCount(PREVIEW_FLOORS).let { Reading(it, spoken(it)) }

    private fun spoken(value: String): String = "${getString(R.string.tile_floors_label)}: $value ${getString(R.string.tile_floors_caption)}"

    private companion object {
        const val PREVIEW_FLOORS = 7L
    }
}
