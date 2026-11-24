package tech.mmarca.openwatch.wear.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tech.mmarca.openwatch.wear.ui.theme.Emphasis

/** How far under its lowest bar a level chart starts, as a share of the range between its lowest and highest. */
private const val LevelHeadroom = 0.25

/** The height of every bar of a level chart whose values are all the same. */
private const val LevelEvenShare = 0.6f

/**
 * Each bar's height as a share of the chart's; null for a value that is
 * missing, which gets no bar.
 *
 * [fromZero] is for totals (steps, metres): a bar is its share of the
 * largest. A level (a heart rate, a blood oxygen, a temperature) moves by a
 * few units far from zero, and from zero its bars would all look the same
 * height: its chart starts a quarter of its range under its lowest value,
 * so the lowest bar is a fifth of the height and the highest is all of it.
 */
internal fun barShares(values: List<Double?>, fromZero: Boolean): List<Float?> {
    val present = values.filterNotNull()
    val max = present.maxOrNull() ?: return values.map { null }
    val min = present.min()
    val floor = if (fromZero) 0.0 else (min - (max - min) * LevelHeadroom).coerceAtLeast(0.0)
    val range = max - floor
    return values.map { value ->
        when {
            value == null -> null
            range > 0.0 -> ((value - floor) / range).toFloat().coerceIn(0f, 1f)
            fromZero -> 0f
            else -> LevelEvenShare
        }
    }
}

/**
 * Bare bars, each centred in an equal slot so a row of equally weighted
 * labels lines up underneath. Totals are scaled to the largest value, from
 * zero; levels ([fromZero] false) from a floor near their range
 * ([barShares]). A zero still draws a stub, so an empty hour reads as
 * "nothing"; a null draws no bar at all, so a day without data reads as a
 * gap in the data.
 */
@Composable
fun BarChart(
    values: List<Double?>,
    color: Color,
    modifier: Modifier = Modifier,
    highlightLast: Boolean = false,
    fromZero: Boolean = true,
) {
    Spacer(
        modifier.drawWithCache {
            val slot = if (values.isEmpty()) 0f else size.width / values.size
            val barWidth = slot * 0.6f
            val stub = 2.dp.toPx()
            val radius = CornerRadius(minOf(barWidth / 2, 3.dp.toPx()))
            val heights = barShares(values, fromZero).map { share -> share?.let { maxOf(it * size.height, stub) } }
            onDrawBehind {
                heights.forEachIndexed { index, height ->
                    if (height == null) return@forEachIndexed
                    val dimmed = highlightLast && index != heights.lastIndex
                    drawRoundRect(
                        color = if (dimmed) color.copy(alpha = Emphasis.fill) else color,
                        topLeft = Offset(index * slot + (slot - barWidth) / 2, size.height - height),
                        size = Size(barWidth, height),
                        cornerRadius = radius,
                    )
                }
            }
        },
    )
}
