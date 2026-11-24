package tech.mmarca.openwatch.wear

import android.content.ComponentName
import android.database.sqlite.SQLiteException
import android.graphics.BitmapFactory
import android.util.Log
import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders.launchAction
import androidx.wear.protolayout.ActionBuilders.stringExtra
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ResourceBuilders.ImageResource
import androidx.wear.protolayout.ResourceBuilders.InlineImageResource
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material3.MaterialScope
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.protolayout.modifiers.clickable
import androidx.wear.tiles.RequestBuilders.ResourcesRequest
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.io.IOException
import java.text.NumberFormat
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext

/**
 * A tile of the watch app. What it shows is read from the process's one
 * [MetricStore] ([WearGraph.store]) on the IO dispatcher, never on the main
 * thread and never from a store of its own: the recorders write to that one
 * in the same process, so a tile never closes it, and it starts no sensor.
 * When the store cannot be read (a locked or corrupt file) the tile says so
 * ([unreadable]) rather than leaving the system's error tile. A tile is
 * current for [FRESHNESS_MILLIS]; Wear OS asks for it again once that has
 * passed, or sooner when the app asks ([TileService.getUpdater]).
 *
 * Two choices are deliberate, both from a Wear OS 4 watch: a plain
 * [TileService] that serves its own resources ([images]) rather than
 * `Material3TileService`, whose images inside the tile never reached that
 * renderer, and the figures in a plain font ([tileValueStyle]) rather than
 * the Material 3 display and numeral styles, whose variable font made the
 * renderer drop the text. The rest of the layout is Material 3, built in
 * [materialScope].
 */
abstract class OpenWatchTileService : TileService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** What the tile shows, read from [store] on the IO dispatcher. [request] carries the tap that asked for it, if one did. */
    internal abstract fun read(request: TileRequest, store: MetricStore): TileFrame

    /** What the tile shows when the store cannot be read. */
    internal abstract fun unreadable(): TileFrame

    /** The images of resources [version], by the ids the layout names. On the IO dispatcher. */
    internal abstract fun images(version: String): Map<String, ImageResource>

    override fun onTileRequest(requestParams: TileRequest): ListenableFuture<Tile> =
        SuspendToFutureAdapter.launchFuture(scope.coroutineContext) {
            val device = requestParams.deviceConfiguration
            val frame = withContext(Dispatchers.IO) {
                try {
                    read(requestParams, wearGraph.store)
                } catch (e: SQLiteException) {
                    Log.e(TAG, "Metric store unreadable", e)
                    unreadable()
                }
            }
            val layout = materialScope(this@OpenWatchTileService, device) { frame.layout(this, device) }
            Tile.Builder()
                .setResourcesVersion(frame.resourcesVersion)
                .setFreshnessIntervalMillis(FRESHNESS_MILLIS)
                .setTileTimeline(Timeline.fromLayoutElement(layout))
                .build()
        }

    override fun onTileResourcesRequest(requestParams: ResourcesRequest): ListenableFuture<Resources> =
        SuspendToFutureAdapter.launchFuture(scope.coroutineContext) {
            val version = requestParams.version
            val images = withContext(Dispatchers.IO) { images(version) }
            Resources.Builder()
                .setVersion(version)
                .apply { images.forEach { (id, image) -> addIdToImageMapping(id, image) } }
                .build()
        }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    /**
     * A tap that opens the watch app: its dashboard, or [route], a screen a
     * tile may open ([WearRoutes.isDeepLinkable]), which [MainActivity] reads
     * from [MainActivity.EXTRA_ROUTE].
     */
    internal fun openApp(route: String? = null): Clickable {
        val activity = ComponentName(this, MainActivity::class.java)
        if (route == null) return clickable(launchAction(activity), id = OPEN_ID)
        return clickable(launchAction(activity, mapOf(MainActivity.EXTRA_ROUTE to stringExtra(route))), id = "$OPEN_ID:$route")
    }

    internal companion object {
        private const val TAG = "OpenWatchTileService"
        private const val OPEN_ID = "open"

        /** How long a shown tile stays current; Wear OS asks again once it has passed. */
        const val FRESHNESS_MILLIS: Long = 5L * 60 * 1000
    }
}

/** What one tile shows: its layout, and the resources version it names, which [OpenWatchTileService.images] serves. */
internal class TileFrame(
    val resourcesVersion: String,
    val layout: MaterialScope.(DeviceParameters) -> LayoutElement,
)

/**
 * One metric's tile: today's value and its curve, read from the store. A tap
 * opens [MainActivity].
 *
 * The curve is a PNG served by [images] under the resources version the tile
 * names, the hash and size of its bytes. The last few PNGs are kept in the
 * cache directory under their versions, so a resources request gets exactly
 * the image its tile was built with, even after a newer tile or a restart of
 * the service.
 */
abstract class MetricTileService : OpenWatchTileService() {

    /** The metric's accent, the colour the phone app draws it in (`ui/theme/Color.kt`). */
    internal abstract val accent: Int

    /** The tile's title, also its title when the store cannot be read. */
    internal abstract val titleRes: Int

    /** Today's tile from [store], read on the IO dispatcher. */
    internal abstract fun read(day: TileDay, chart: TileChart, store: MetricStore): MetricTileContent

    override fun read(request: TileRequest, store: MetricStore): TileFrame {
        val device = request.deviceConfiguration
        val density = device.screenDensity
        val chartHeightDp = (device.screenHeightDp * CHART_HEIGHT_SHARE).coerceIn(MIN_CHART_HEIGHT_DP, MAX_CHART_HEIGHT_DP)
        val chart = TileChart(
            widthPx = (device.screenWidthDp * CHART_WIDTH_SHARE * density).roundToInt(),
            heightPx = (chartHeightDp * density).roundToInt(),
            density = density,
            accent = accent,
        )
        val today = read(TileDay.of(System.currentTimeMillis(), ZoneId.systemDefault()), chart, store)
        return frameOf(if (today.chart == null || keep(today.chart)) today else today.withoutChart())
    }

    /** A dash, rather than the system's error tile. */
    override fun unreadable(): TileFrame = frameOf(unavailable(getString(R.string.tile_read_failed)))

    private fun frameOf(tile: MetricTileContent): TileFrame {
        val open = openApp()
        return TileFrame(tile.chart?.let(::versionOf) ?: NO_CHART_VERSION) { device ->
            metricTileLayout(tile, open, device.screenDensity)
        }
    }

    override fun images(version: String): Map<String, ImageResource> {
        if (version == NO_CHART_VERSION) return emptyMap()
        val png = pngFor(version) ?: return emptyMap()
        return mapOf(CHART_RESOURCE_ID to imageOf(png))
    }

    internal fun formatCount(count: Long): String = NumberFormat.getIntegerInstance().format(count)

    /** A tile without a value: a dash, [reason] as the caption, no curve. */
    internal fun unavailable(reason: String): MetricTileContent {
        val title = getString(titleRes)
        return MetricTileContent(title, TILE_NO_VALUE, reason, accent, "$title: $reason", chart = null)
    }

    /**
     * The PNG kept under [version]. If it is gone (the cache was cleared),
     * the tile is asked for again rather than drawn anew: a new drawing ends
     * at a later now, so it would not be that version's image.
     */
    private fun pngFor(version: String): ByteArray? =
        kept(version) ?: run {
            getUpdater(this).requestUpdate(javaClass)
            null
        }

    /**
     * Keeps [chart] under its version for the resources request, written whole
     * before it gets its name, and drops all but this tile's [KEPT_VERSIONS]
     * newest, and temporary files a crash left behind. A failure only costs
     * the chart: false, and the tile goes without its curve, since a tile
     * naming a version with no file would only be asked for again.
     */
    private fun keep(chart: TileChart.Png): Boolean =
        try {
            val dir = File(cacheDir, CACHE_DIR).apply { mkdirs() }
            val file = File(dir, fileName(versionOf(chart)))
            if (!file.isFile) {
                // A temporary file of its own, so two requests drawing the same version never share one.
                val partial = File.createTempFile(file.name, PART_SUFFIX, dir)
                try {
                    partial.writeBytes(chart.bytes)
                    partial.renameTo(file)
                } finally {
                    partial.delete()
                }
            }
            file.setLastModified(System.currentTimeMillis())
            dir.listFiles { kept -> kept.name.startsWith(filePrefix) && kept.name.endsWith(PNG_SUFFIX) }
                ?.sortedByDescending(File::lastModified)
                ?.drop(KEPT_VERSIONS)
                ?.forEach(File::delete)
            val abandoned = System.currentTimeMillis() - ABANDONED_PART_MILLIS
            dir.listFiles { part -> part.name.startsWith(filePrefix) && part.name.endsWith(PART_SUFFIX) && part.lastModified() < abandoned }
                ?.forEach(File::delete)
            file.isFile
        } catch (e: IOException) {
            Log.w(TAG, "Chart not kept", e)
            false
        }

    /** The PNG kept under [version], or null when it is gone or cannot be read. */
    private fun kept(version: String): ByteArray? =
        try {
            File(File(cacheDir, CACHE_DIR), fileName(version)).takeIf(File::isFile)?.readBytes()
        } catch (e: IOException) {
            Log.w(TAG, "Kept chart unreadable", e)
            null
        }

    private val filePrefix: String get() = "${javaClass.simpleName}-"

    private fun fileName(version: String): String = "$filePrefix$version$PNG_SUFFIX"

    private fun versionOf(chart: TileChart.Png): String =
        "${chart.bytes.contentHashCode().toUInt().toString(RADIX)}-${chart.bytes.size.toString(RADIX)}"

    private fun imageOf(png: ByteArray): ImageResource {
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(png, 0, png.size, size)
        // No format set: a PNG of unspecified format is what every tile renderer decodes.
        return ImageResource.Builder()
            .setInlineResource(
                InlineImageResource.Builder()
                    .setData(png)
                    .setWidthPx(size.outWidth)
                    .setHeightPx(size.outHeight)
                    .build(),
            )
            .build()
    }

    internal companion object {
        /** The one image a metric tile has. */
        const val CHART_RESOURCE_ID = "chart"

        private const val TAG = "MetricTileService"
        private const val NO_CHART_VERSION = "none"
        private const val CACHE_DIR = "tile-charts"
        private const val PNG_SUFFIX = ".png"
        private const val PART_SUFFIX = ".part"

        /** A temporary file this old was left by a write that never finished. */
        private const val ABANDONED_PART_MILLIS: Long = 10L * 60 * 1000

        /** Enough for a tile request that is overtaken by the next before its resources are asked for. */
        private const val KEPT_VERSIONS = 4
        private const val RADIX = 36

        /** About the width the main slot leaves on a round screen. */
        private const val CHART_WIDTH_SHARE = 0.72f

        /** The curve's share of the screen height, so a small screen keeps room for the text around it. */
        private const val CHART_HEIGHT_SHARE = 0.24f
        private const val MIN_CHART_HEIGHT_DP = 40f
        private const val MAX_CHART_HEIGHT_DP = 56f
    }
}

/** A dash where a tile has no value; the caption says why. */
internal const val TILE_NO_VALUE = "–"
