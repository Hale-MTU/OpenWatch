package tech.mmarca.openwatch.wear

import android.app.PendingIntent
import android.content.Intent
import android.database.sqlite.SQLiteException
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.text.NumberFormat
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One metric as a watch face complication: a short text with the metric's
 * icon, its value and its unit. It reads what the metric's tile reads
 * ([readHeartRateNow], [readStepsToday]) from the process's one
 * [MetricStore], on the IO dispatcher, and starts no sensor: the recorders in
 * [WearAppService] write whether a complication is shown or not. The system
 * asks again about every five minutes (the manifest's update period), and a
 * tap opens [MainActivity].
 */
abstract class MetricComplicationService : SuspendingComplicationDataSourceService() {

    /** The value as the slot shows it, and as a screen reader says it. */
    internal class Reading(val value: String, val spoken: String)

    internal abstract val iconRes: Int

    /** The unit, the short text's title. */
    internal abstract val unitRes: Int

    /** The reading now from [store]; blocks on the store. */
    internal abstract fun read(store: MetricStore, nowMillis: Long): Reading

    /** What the watch face editor shows before the first request. */
    internal abstract fun preview(): Reading

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.SHORT_TEXT) return null
        val reading = withContext(Dispatchers.IO) {
            try {
                read(wearGraph.store, System.currentTimeMillis())
            } catch (_: SQLiteException) {
                // A locked or corrupt file: a dash rather than the system's empty slot; the next request tries again.
                Reading(TILE_NO_VALUE, getString(R.string.tile_read_failed))
            }
        }
        return shortText(reading)
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.SHORT_TEXT) shortText(preview()) else null

    internal fun formatCount(count: Long): String = NumberFormat.getIntegerInstance().format(count)

    private fun shortText(reading: Reading): ComplicationData =
        ShortTextComplicationData.Builder(text(reading.value), text(reading.spoken))
            .setTitle(text(getString(unitRes)))
            .setMonochromaticImage(MonochromaticImage.Builder(Icon.createWithResource(this, iconRes)).build())
            .setTapAction(openApp())
            .build()

    private fun text(value: String) = PlainComplicationText.Builder(value).build()

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this,
        // One request code per complication, apart from the notifications' 0 and 4.
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
        is TileHeartRate.Recent -> Reading(now.bpm.toString(), spoken(now))
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

/** The steps since midnight on this watch, as the steps tile counts them. */
class StepsComplicationService : MetricComplicationService() {

    override val iconRes: Int = R.drawable.ic_metric_steps

    override val unitRes: Int = R.string.unit_steps

    override fun read(store: MetricStore, nowMillis: Long): Reading =
        when (val today = readStepsToday(this, store, TileDay.of(nowMillis, ZoneId.systemDefault()))) {
            is StepsToday.Missing -> Reading(TILE_NO_VALUE, getString(today.reason))
            is StepsToday.Counted -> formatCount(today.total).let { Reading(it, spoken(it)) }
        }

    override fun preview(): Reading = formatCount(PREVIEW_STEPS).let { Reading(it, spoken(it)) }

    private fun spoken(value: String): String = "${getString(R.string.tile_steps_label)}: $value ${getString(R.string.tile_steps_caption)}"

    private companion object {
        const val PREVIEW_STEPS = 6_480L
    }
}
