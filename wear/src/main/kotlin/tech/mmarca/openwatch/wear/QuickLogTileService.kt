package tech.mmarca.openwatch.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.DrawableRes
import androidx.core.content.edit
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ResourceBuilders.AndroidImageResourceByResId
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.layout.box
import androidx.wear.protolayout.material3.ButtonColors
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.ProgressIndicatorColors
import androidx.wear.protolayout.material3.Typography
import androidx.wear.protolayout.material3.buttonGroup
import androidx.wear.protolayout.material3.circularProgressIndicator
import androidx.wear.protolayout.material3.icon
import androidx.wear.protolayout.material3.iconButton
import androidx.wear.protolayout.material3.primaryLayout
import androidx.wear.protolayout.material3.text
import androidx.wear.protolayout.modifiers.LayoutModifier
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.protolayout.modifiers.contentDescription
import androidx.wear.protolayout.modifiers.loadAction
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.RequestBuilders.TileRequest
import java.text.NumberFormat
import java.time.ZoneId
import kotlin.math.roundToInt
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * Logging without opening the app: today's water as a ring around the
 * screen toward the day's goal, a button that adds a glass right on the
 * tile, a shortcut to a heart rate measurement on a watch that can take one,
 * and one to the quick log. The glass goes through [EntryLog], the entries'
 * one writer, as one logged in the app does; a glass logged anywhere asks
 * this tile to show the new total ([WearGraph.entries]).
 */
class QuickLogTileService : OpenWatchTileService() {

    override fun read(request: TileRequest, store: MetricStore): TileFrame {
        val graph = wearGraph
        val taps = WaterTaps(this)
        taps.onTap(request.currentState.lastClickableId) {
            graph.entries.log(WearLinkEntry.Type.WATER, DisplayPreferences.WATER_SERVING_ML)
        }
        val water = readWaterToday(store, TileDay.of(System.currentTimeMillis(), ZoneId.systemDefault()))
        val units = graph.settings.value.unitSystem
        val total = getString(R.string.quicklog_water_today, water(water, units), water(GOAL_ML, units), waterUnit(units))
        return frameOf(water / GOAL_ML, total, units, canMeasure = graph.sensors.heartRate, addWaterId = taps.id)
    }

    /** No ring, and why, rather than the system's error tile; the buttons still work. */
    override fun unreadable(): TileFrame {
        val graph = wearGraph
        val failed = getString(R.string.tile_read_failed)
        return frameOf(0.0, failed, graph.settings.value.unitSystem, canMeasure = graph.sensors.heartRate, addWaterId = WaterTaps(this).id)
    }

    override fun images(version: String): Map<String, ImageResource> = mapOf(
        ICON_WATER to drawable(R.drawable.ic_metric_hydration),
        ICON_HEART to drawable(R.drawable.ic_metric_heart_rate),
        ICON_ADD to drawable(R.drawable.ic_tile_add),
    )

    private fun frameOf(progress: Double, total: String, units: UnitSystem, canMeasure: Boolean, addWaterId: String): TileFrame {
        val title = getString(R.string.metric_hydration)
        val addWater = clickable(loadAction(), id = addWaterId)
        val addWaterLabel = getString(R.string.quicklog_add_water, water(DisplayPreferences.WATER_SERVING_ML, units), waterUnit(units))
        val measure = openApp(WearRoutes.measurement(SpotKind.HEART_RATE)).takeIf { canMeasure }
        val measureLabel = getString(R.string.measure_heart_rate)
        val quickLog = openApp(WearRoutes.QUICK_LOG)
        val quickLogLabel = getString(R.string.quicklog_title)
        return TileFrame(RESOURCES_VERSION) {
            box(
                circularProgressIndicator(
                    staticProgress = progress.toFloat().coerceIn(0f, 1f),
                    colors = ProgressIndicatorColors(HYDRATION.argb, HYDRATION_TRACK.argb, HYDRATION.argb),
                ),
                primaryLayout(
                    titleSlot = { text(title.layoutString, color = HYDRATION.argb) },
                    mainSlot = {
                        buttonGroup {
                            buttonGroupItem { shortcut(addWater, ICON_WATER, HYDRATION, addWaterLabel) }
                            if (measure != null) buttonGroupItem { shortcut(measure, ICON_HEART, HEART, measureLabel) }
                            buttonGroupItem { shortcut(quickLog, ICON_ADD, TONAL, quickLogLabel) }
                        }
                    },
                    bottomSlot = { text(total.layoutString, typography = Typography.BODY_SMALL, color = colorScheme.onSurfaceVariant) },
                ),
                width = expand(),
                height = expand(),
            )
        }
    }

    /** A round button showing [icon] on [background], [description] for a screen reader. */
    private fun MaterialScope.shortcut(onClick: Clickable, icon: String, background: Int, description: String): LayoutElement =
        iconButton(
            onClick = onClick,
            iconContent = { icon(icon) },
            modifier = LayoutModifier.contentDescription(description),
            colors = ButtonColors(background.argb, ON_BUTTON.argb, ON_BUTTON.argb, ON_BUTTON.argb),
        )

    /** [millilitres] in the wearer's unit, as the quick log screen prints it. */
    private fun water(millilitres: Double, units: UnitSystem): String =
        NumberFormat.getIntegerInstance().format(units.water(millilitres).roundToInt())

    private fun waterUnit(units: UnitSystem): String = getString(
        when (units) {
            UnitSystem.METRIC -> R.string.unit_ml
            UnitSystem.IMPERIAL -> R.string.unit_fl_oz
        },
    )

    private fun drawable(@DrawableRes id: Int): ImageResource =
        ImageResource.Builder()
            .setAndroidResourceByResId(AndroidImageResourceByResId.Builder().setResourceId(id).build())
            .build()

    private companion object {
        /** The three icons never change: one version for every tile. A new icon is a new version. */
        const val RESOURCES_VERSION = "1"
        const val ICON_WATER = "water"
        const val ICON_HEART = "heart"
        const val ICON_ADD = "add"

        val GOAL_ML: Double = DisplayPreferences.DEFAULT_WATER_GOAL_ML.toDouble()

        /** `HydrationColor` and `HeartColor`, as the app draws them; the track is the water's colour, faint. */
        const val HYDRATION: Int = 0xFF2E97C9.toInt()
        const val HYDRATION_TRACK: Int = 0x382E97C9
        const val HEART: Int = 0xFFD2497B.toInt()

        /** `SurfaceContainerDark`, the app's tonal button, and white on every button. */
        const val TONAL: Int = 0xFF2B2D30.toInt()
        const val ON_BUTTON: Int = 0xFFFFFFFF.toInt()
    }
}

/**
 * The quick log tile's add-water button. Its click id carries a count,
 * `add_water_<n>`, which moves on with each glass, so a tile request that
 * repeats the id of a tap already logged (a refresh, not a tap) logs
 * nothing. The count is moved on before the glass is logged: a process that
 * dies in between loses that glass rather than logging it twice.
 */
internal class WaterTaps(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private val count: Int get() = prefs.getInt(KEY_CLICKS, 0)

    /** The id the button carries now. */
    val id: String get() = idOf(count)

    /** Runs [log] once when [clickedId] is the button's id now, and moves the id on. Writes the file: not on the main thread. */
    fun onTap(clickedId: String, log: () -> Unit) {
        synchronized(LOCK) {
            val now = count
            if (clickedId != idOf(now)) return
            prefs.edit(commit = true) { putInt(KEY_CLICKS, now + 1) }
        }
        log()
    }

    companion object {
        /** Final once shipped: the file and its key are read by every later build. */
        const val PREFS = "quick_log_tile"
        const val KEY_CLICKS = "add_water_clicks"

        /** One per process: two tile requests of one tap must not both log it. */
        private val LOCK = Any()

        fun idOf(count: Int): String = "add_water_$count"
    }
}
