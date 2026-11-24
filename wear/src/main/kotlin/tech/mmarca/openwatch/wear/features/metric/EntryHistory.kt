package tech.mmarca.openwatch.wear.features.metric

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumnScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.ListSubHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.ui.components.formatMetricValue
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * The entries of one type a metric's detail lists: what the wearer measured
 * or logged on the watch. The heart rate has none: its detail draws the
 * day's curve.
 */
internal fun MetricTile.entryType(): WearLinkEntry.Type? = when (this) {
    MetricTile.HRV -> WearLinkEntry.Type.HRV
    MetricTile.WEIGHT -> WearLinkEntry.Type.WEIGHT
    MetricTile.HYDRATION -> WearLinkEntry.Type.WATER
    MetricTile.BLOOD_OXYGEN -> WearLinkEntry.Type.BLOOD_OXYGEN
    MetricTile.RESPIRATORY_RATE -> WearLinkEntry.Type.RESPIRATORY_RATE
    else -> null
}

/** One line of the list: when, and the value in the metric's base unit. [day] is null for today. */
internal data class RecentEntry(val epochMillis: Long, val day: LocalDate?, val value: Double)

/**
 * The newest [count] entries of [metric], newest first. A measurement that
 * does not show on the tile (the tile shows the night's value where there is
 * one) is still found here.
 */
internal fun recentEntries(
    entries: List<WearLinkEntry>,
    metric: MetricTile,
    today: LocalDate,
    zone: ZoneId,
    count: Int = RECENT_COUNT,
): List<RecentEntry> {
    val type = metric.entryType() ?: return emptyList()
    return entries.asSequence()
        .filter { it.type == type }
        .sortedByDescending { it.epochMillis }
        .take(count)
        .map { entry ->
            val day = Instant.ofEpochMilli(entry.epochMillis).atZone(zone).toLocalDate()
            RecentEntry(entry.epochMillis, day.takeIf { it != today }, entry.value)
        }
        .toList()
}

/** The "Latest entries" section of a metric's detail: each entry's time, its day where it is not today's, and its value. */
internal fun TransformingLazyColumnScope.recentEntryItems(recent: List<RecentEntry>, metric: MetricTile, unitSystem: UnitSystem, unit: String) {
    if (recent.isEmpty()) return
    item { ListSubHeader { Text(stringResource(R.string.detail_recent_entries)) } }
    items(recent, key = { it.epochMillis }) { entry ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = entryTime(entry),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(text = "${formatMetricValue(metric, entry.value, unitSystem)} $unit", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The entry's clock time in the watch's format, after its day where it is not today's. */
@Composable
private fun entryTime(entry: RecentEntry): String {
    val context = LocalContext.current
    return remember(entry) {
        val time = DateFormat.getTimeFormat(context).format(Date(entry.epochMillis))
        val day = entry.day ?: return@remember time
        val locale = Locale.getDefault()
        "${day.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMMM"), locale))} $time"
    }
}

private const val RECENT_COUNT = 5
