package tech.mmarca.openwatch.wear.features.quicklog

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.MonitorWeight
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.UnitSystem
import tech.mmarca.openwatch.wear.ui.components.formatCount
import tech.mmarca.openwatch.wear.ui.components.formatMetricValue
import tech.mmarca.openwatch.wear.ui.components.metricUnitLabel
import tech.mmarca.openwatch.wear.ui.theme.HeartColor
import tech.mmarca.openwatch.wear.ui.theme.HydrationColor
import tech.mmarca.openwatch.wear.ui.theme.MindfulnessColor
import tech.mmarca.openwatch.wear.ui.theme.VitalsColor
import tech.mmarca.openwatch.wear.ui.theme.WeightColor
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkEntry.Type

val Type.icon: ImageVector
    get() = when (this) {
        Type.WATER -> Icons.Outlined.WaterDrop
        Type.WEIGHT -> Icons.Outlined.MonitorWeight
        Type.BLOOD_PRESSURE -> Icons.Outlined.Speed
        Type.MINDFULNESS -> Icons.Outlined.SelfImprovement
        Type.HEART_RATE -> Icons.Outlined.Favorite
        Type.HRV -> Icons.Outlined.Insights
    }

val Type.color: Color
    get() = when (this) {
        Type.WATER -> HydrationColor
        Type.WEIGHT -> WeightColor
        Type.BLOOD_PRESSURE -> VitalsColor
        Type.MINDFULNESS -> MindfulnessColor
        Type.HEART_RATE -> HeartColor
        Type.HRV -> VitalsColor
    }

/** The entry's value with its unit, such as "72.4 kg" or "121/79 mmHg", in [unitSystem]. */
@Composable
fun formatEntry(entry: WearLinkEntry, unitSystem: UnitSystem): String = when (entry.type) {
    Type.WATER -> metricValue(MetricTile.HYDRATION, entry.value, unitSystem)
    Type.WEIGHT -> metricValue(MetricTile.WEIGHT, entry.value, unitSystem)
    Type.HEART_RATE -> metricValue(MetricTile.HEART_RATE, entry.value, unitSystem)
    Type.HRV -> metricValue(MetricTile.HRV, entry.value, unitSystem)
    Type.MINDFULNESS -> entry.value.roundToInt().let { pluralStringResource(R.plurals.quicklog_minutes, it, it) }
    Type.BLOOD_PRESSURE -> stringResource(
        R.string.quicklog_blood_pressure_value,
        formatCount(entry.value.roundToInt()),
        formatCount(entry.second?.roundToInt() ?: 0),
    )
}

@Composable
private fun metricValue(metric: MetricTile, value: Double, unitSystem: UnitSystem): String =
    "${formatMetricValue(metric, value, unitSystem)} ${stringResource(metricUnitLabel(metric, unitSystem))}"
