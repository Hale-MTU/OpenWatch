package tech.mmarca.openwatch.wear.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import tech.mmarca.openwatch.wear.LinePoint

/**
 * A bare trend line over time: no axes, no labels. Each of [runs] is drawn
 * as one stroke with its points where they fall in time ([LinePoint.at]),
 * and nothing joins two runs: the gap is time without a reading. A run of
 * one point is a dot. The height is scaled to the lowest and highest value.
 */
@Composable
fun Sparkline(
    runs: List<List<LinePoint>>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Spacer(
        modifier.drawWithCache {
            // Built once per size and line, not once per frame.
            val stroke = 2.dp.toPx()
            val path = Path()
            val dots = mutableListOf<Offset>()
            val values = runs.flatMap { run -> run.map { it.value } }
            if (values.isNotEmpty()) {
                val min = values.min()
                val span = (values.max() - min).coerceAtLeast(1.0)
                val drawableWidth = size.width - stroke
                val drawableHeight = size.height - stroke
                fun place(point: LinePoint) = Offset(
                    x = stroke / 2 + drawableWidth * point.at.coerceIn(0f, 1f),
                    y = stroke / 2 + drawableHeight * (1f - ((point.value - min) / span).toFloat()),
                )
                for (run in runs) {
                    if (run.size == 1) dots += place(run.single())
                    run.forEachIndexed { index, point ->
                        val at = place(point)
                        if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
                    }
                }
            }
            onDrawBehind {
                drawPath(
                    path = path,
                    color = color,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                dots.forEach { drawCircle(color = color, radius = stroke / 2, center = it) }
            }
        },
    )
}
