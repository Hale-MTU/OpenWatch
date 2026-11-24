package tech.mmarca.openwatch.wear

import androidx.wear.protolayout.DimensionBuilders.dp
import androidx.wear.protolayout.LayoutElementBuilders.HORIZONTAL_ALIGN_CENTER
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.layout.basicText
import androidx.wear.protolayout.layout.column
import androidx.wear.protolayout.layout.row
import androidx.wear.protolayout.layout.spacer
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.material3.textEdgeButton
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders.TileRequest
import java.text.NumberFormat
import java.time.ZoneId

/**
 * Today at a glance: the steps since midnight and the heart rate now, side
 * by side in their metrics' accents. Each is read as its own tile and
 * complication read it ([readStepsToday], [readHeartRateNow]), so they show
 * the same numbers, a dash with the reason where there is none. A tap
 * anywhere, or on Open, opens the watch app's dashboard.
 */
class MainVitalsTileService : OpenWatchTileService() {

    override fun read(request: TileRequest, store: MetricStore): TileFrame {
        val now = System.currentTimeMillis()
        val steps = when (val today = readStepsToday(this, store, TileDay.of(now, ZoneId.systemDefault()))) {
            is StepsToday.Missing -> getString(today.reason).let { TileFigure(TILE_NO_VALUE, it, it) }
            is StepsToday.Counted -> {
                val value = NumberFormat.getIntegerInstance().format(today.total)
                val unit = getString(R.string.unit_steps)
                TileFigure(value, unit, "$value $unit")
            }
        }
        return frameOf(steps, heartRateFigure(readHeartRateNow(this, store, now)))
    }

    /** Dashes, and why, rather than the system's error tile. */
    override fun unreadable(): TileFrame {
        val failed = getString(R.string.tile_read_failed)
        return frameOf(TileFigure(TILE_NO_VALUE, failed, failed), TileFigure(TILE_NO_VALUE, failed, failed))
    }

    /** No image: the figures are text. */
    override fun images(version: String): Map<String, ImageResource> = emptyMap()

    private fun frameOf(steps: TileFigure, heartRate: TileFigure): TileFrame {
        val title = getString(R.string.dashboard_today)
        val open = openApp()
        val openLabel = getString(R.string.tile_open)
        // One sentence for a screen reader; a figure without a value says only why.
        val description = "$title: ${steps.spoken}, ${heartRate.spoken}"
        return TileFrame(RESOURCES_VERSION) { device ->
            val figureWidth = device.screenWidthDp * FIGURE_WIDTH_SHARE
            primaryLayout(
                titleSlot = { text(title.layoutString) },
                mainSlot = {
                    row(
                        figure(steps, STEPS_ACCENT, figureWidth),
                        spacer(width = dp(FIGURE_GAP_DP)),
                        figure(heartRate, HEART_ACCENT, figureWidth),
                        modifier = LayoutModifier.contentDescription(description),
                    )
                },
                bottomSlot = { textEdgeButton(onClick = open) { text(openLabel.layoutString) } },
                onClick = open,
            )
        }
    }

    /** A figure over its caption, [widthDp] wide, so a long reason wraps instead of pushing its neighbour off. */
    private fun MaterialScope.figure(figure: TileFigure, accent: Int, widthDp: Float): LayoutElement =
        column(
            basicText(figure.value.layoutString, tileValueStyle(accent, FIGURE_SIZE_SP), maxLines = 1),
            text(figure.caption.layoutString, typography = Typography.BODY_SMALL, color = colorScheme.onSurfaceVariant, maxLines = 2),
            width = dp(widthDp),
            horizontalAlignment = HORIZONTAL_ALIGN_CENTER,
        )

    private companion object {
        /** Text only: the layout names no image, so one version serves every tile. */
        const val RESOURCES_VERSION = "1"

        /** `StepsColor` and `HeartColor`, as the Steps and Heart rate tiles draw them. */
        const val STEPS_ACCENT: Int = 0xFF3F9A63.toInt()
        const val HEART_ACCENT: Int = 0xFFD2497B.toInt()

        /** Each figure's share of the screen's width: two fit side by side where the round screen is widest. */
        const val FIGURE_WIDTH_SHARE = 0.36f
        const val FIGURE_GAP_DP = 8f
        const val FIGURE_SIZE_SP = 28f
    }
}
