package tech.mmarca.openwatch.wear

import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * Navigation routes. Each feature package declares its own destinations, a
 * `NavGraphBuilder` extension in its `…Navigation.kt` that `OpenWatchWearApp`
 * calls; the routes stay here, so one feature can open another's, and a tile
 * can open some of them ([isDeepLinkable]).
 */
object WearRoutes {
    const val DASHBOARD = "dashboard"

    /** Which of the dashboard's tiles show, and in which order. */
    const val TILE_EDITOR = "tile_editor"
    const val METRIC_ARG = "metric"
    const val METRIC = "metric/{$METRIC_ARG}"
    fun metric(metric: MetricTile) = "metric/${metric.name}"

    /** The metric a [METRIC_ARG] names; null for a name this build has no metric for, and for none. */
    fun metricOf(argument: String?): MetricTile? = MetricTile.entries.firstOrNull { it.name == argument }

    /** The watch's settings, and the phone and sensor access screens they open. */
    const val SETTINGS = "settings"
    const val PHONE = "phone"
    const val SENSORS = "sensors"

    /** The link and recorder status: the recording details, which the sensor access screen opens. */
    const val STATUS = "status"

    /** The workout: the sport picker, the running workout, and the summary of a saved one. */
    const val WORKOUT = "workout"

    /** The quick log: water at a tap, and the ways to the other entries. */
    const val QUICK_LOG = "quick_log"

    /** Which of the quick log's entries show, and in which order. */
    const val QUICK_LOG_EDITOR = "quick_log_editor"
    const val LOG_WEIGHT = "log_weight"
    const val LOG_BLOOD_PRESSURE = "log_blood_pressure"
    const val LOG_VALUE_ARG = "type"

    /** One value of a type the quick log takes one number of, `log_value/BODY_FAT`. */
    const val LOG_VALUE = "log_value/{$LOG_VALUE_ARG}"
    fun logValue(type: WearLinkEntry.Type) = "log_value/${type.name}"

    /** The entry type a [LOG_VALUE_ARG] names; null for a name this build has no type for, and for none. */
    fun entryTypeOf(argument: String?): WearLinkEntry.Type? = WearLinkEntry.Type.entries.firstOrNull { it.name == argument }

    /** The guided breathing minute, logged as a mindful session when it ends. */
    const val BREATHE = "breathe"

    /** The spot measurements this watch can take. */
    const val MEASURE = "measure"
    const val MEASUREMENT_ARG = "measurement"

    /** One spot measurement, `measure/HEART_RATE`, from the list or a metric's detail. */
    const val MEASUREMENT = "measure/{$MEASUREMENT_ARG}"
    fun measurement(kind: SpotKind) = "measure/${kind.name}"

    /** The measurement a [MEASUREMENT_ARG] names; null for a name this build has none for, and for none. */
    fun spotKindOf(argument: String?): SpotKind? = SpotKind.entries.firstOrNull { it.name == argument }

    /** The screens a caller outside the app, a tile, may open: the logging and the measuring ones. */
    private val DEEP_LINKABLE = setOf(QUICK_LOG, LOG_WEIGHT, LOG_BLOOD_PRESSURE, BREATHE, MEASURE)

    /**
     * Whether a caller outside the app, a tile, may open [route]
     * (`MainActivity.EXTRA_ROUTE`): a logging or a measuring screen, a
     * measurement only by a kind this build has. Anything else is ignored, and
     * the app opens where it would have.
     */
    fun isDeepLinkable(route: String?): Boolean =
        route in DEEP_LINKABLE || SpotKind.entries.any { route == measurement(it) }
}
