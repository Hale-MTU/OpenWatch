package tech.mmarca.openwatch.devices.garmin

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.DateTimeParseException
import java.time.format.SignStyle
import java.time.temporal.ChronoField
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One drink the hydration widget logged, at the watch's local time. A subtraction is negative. */
public data class GarminHydrationEntry(val at: LocalDateTime, val milliliters: Double)

/** One day as the widget shows it: what was drunk, when the newest entry was made, and the day's goal. */
public data class GarminHydrationDay(
    val totalMilliliters: Double,
    val lastEntryAt: LocalDateTime?,
    val goalMilliliters: Double = DEFAULT_GOAL_MILLILITERS,
) {
    public companion object {
        /** Two litres, Garmin's default, while the user has set no water goal. */
        public const val DEFAULT_GOAL_MILLILITERS: Double = 2_000.0
    }
}

/**
 * Where the hydration widget's entries go, and what answers its asks: the session's owner keeps
 * them for one watch. Called on the session's thread, so nothing here waits on Health Connect.
 */
public interface GarminHydrationLog {
    /** Keeps [entry]. The watch may send one again; the same entry is kept once. */
    public fun log(entry: GarminHydrationEntry)

    /** What the watch logged on [date], and the goal to show beside it. */
    public fun day(date: LocalDate): GarminHydrationDay

    /** The containers and unit the watch last sent, as their JSON, or null before it sent any. */
    public fun settings(): String?

    public fun keepSettings(json: String)
}

/**
 * Answers the watch's hydration widget, which talks to Garmin Connect's hydration service through
 * the phone: a drink logged, the day's total, the widget's containers. A drink goes to [log], and
 * every answer comes from what it kept, so the widget shows the day the phone has. The paths and
 * the answers' shapes are Gadgetbridge's (`HydrationInterceptor`).
 */
public class GarminHydrationInterceptor(private val log: GarminHydrationLog) : GarminHttpInterceptor {

    override fun supports(request: GarminHttpRequest): Boolean =
        request.domain in DOMAINS &&
            (request.path == PATH_REGISTER || request.path == PATH_SETTINGS || request.path.startsWith(PATH_SUMMARY))

    override fun handle(request: GarminHttpRequest): GarminHttpResponse? {
        val path = request.path
        // A plain GET names no method.
        val read = request.method == null || request.method == GarminHttpMethod.GET
        val write = request.method == GarminHttpMethod.PUT || request.method == GarminHttpMethod.POST
        val json: Any? = when {
            write && path == PATH_REGISTER -> JSONObject()
            read && path == PATH_SETTINGS -> settings()
            write && path == PATH_LOG -> entryOf(request.body)?.let { entry ->
                log.log(entry)
                GarminLog.log("[GARMIN-HYDRATION] the watch logged a drink")
                summary(entry.at.toLocalDate())
            }
            read && path.startsWith(PATH_DAILY) -> dateOf(path.removePrefix(PATH_DAILY))?.let(::summary)
            write && path.startsWith(PATH_ALL_DATA) -> dateOf(path.removePrefix(PATH_ALL_DATA))?.let { date ->
                bodyOf(request.body)?.let(::keepSettings)
                summary(date)
            }
            else -> null
        }
        if (json == null) {
            GarminLog.log("[GARMIN-HYDRATION] refused an ask the widget made (method ${request.method})")
            return null
        }
        return GarminHttpResponse(
            body = json.toString().toByteArray(Charsets.UTF_8),
            headers = mapOf("Content-Type" to "application/json"),
        )
    }

    private fun entryOf(body: ByteArray?): GarminHydrationEntry? {
        val json = bodyOf(body) ?: return null
        val at = try {
            LocalDateTime.parse(json.optString("timestampLocal"), TIMESTAMP_PARSER)
        } catch (_: DateTimeParseException) {
            return null
        }
        val milliliters = json.optDouble("valueInML")
        return if (milliliters.isFinite()) GarminHydrationEntry(at, milliliters) else null
    }

    private fun dateOf(text: String): LocalDate? = try {
        LocalDate.parse(text)
    } catch (_: DateTimeParseException) {
        null
    }

    private fun bodyOf(body: ByteArray?): JSONObject? = try {
        body?.let { JSONObject(it.toString(Charsets.UTF_8)) }
    } catch (_: JSONException) {
        null
    }

    /** The day's summary, Garmin Connect's shape, with the day's goal as the log gives it. */
    private fun summary(date: LocalDate): JSONObject {
        val day = log.day(date)
        return JSONObject().apply {
            put("userId", USER_ID)
            put("calendarDate", date.toString())
            put("valueInML", day.totalMilliliters)
            put("goalInML", day.goalMilliliters)
            put("dailyAverageinML", JSONObject.NULL)
            put("lastEntryTimestampLocal", day.lastEntryAt?.format(TIMESTAMP_FORMATTER) ?: JSONObject.NULL)
            put("sweatLossInML", JSONObject.NULL)
            put("activityIntakeInML", 0.0)
        }
    }

    /** What the watch last set, over the defaults: three containers of 250, 500 and 750 ml. */
    private fun settings(): JSONObject {
        val kept = log.settings()?.let { text -> runCatching { JSONObject(text) }.getOrNull() }
        return JSONObject().apply {
            put("hydrationMeasurementUnit", kept?.optString(KEY_UNIT)?.takeIf { it.isNotEmpty() } ?: UNIT_MILLILITER)
            put(
                KEY_CONTAINERS,
                kept?.optJSONArray(KEY_CONTAINERS) ?: JSONArray().apply {
                    for (volume in DEFAULT_CONTAINERS_ML) {
                        put(JSONObject().put("volume", volume).put("unit", UNIT_MILLILITER))
                    }
                },
            )
            put(KEY_AUTO_GOAL, kept?.optBoolean(KEY_AUTO_GOAL) ?: false)
        }
    }

    /** Keeps the containers, the unit and the auto-goal switch from what the watch sent; the rest is the day's. */
    private fun keepSettings(body: JSONObject) {
        val kept = JSONObject()
        body.optJSONArray(KEY_CONTAINERS)?.let { kept.put(KEY_CONTAINERS, it) }
        body.optString(KEY_UNIT).takeIf { it.isNotEmpty() }?.let { kept.put(KEY_UNIT, it) }
        if (body.has(KEY_AUTO_GOAL)) kept.put(KEY_AUTO_GOAL, body.optBoolean(KEY_AUTO_GOAL))
        if (kept.length() > 0) log.keepSettings(kept.toString())
    }

    private companion object {
        val DOMAINS = setOf("connectapi.garmin.com", "connectapi.garmin.cn")
        const val PATH_REGISTER = "/pushnotification-service/ciq/hydration/register"
        const val PATH_SETTINGS = "/userprofile-service/userprofile/user-settings/hydration"
        const val PATH_SUMMARY = "/usersummary-service/usersummary/hydration/"
        const val PATH_LOG = PATH_SUMMARY + "log"
        const val PATH_DAILY = PATH_SUMMARY + "daily/"
        const val PATH_ALL_DATA = PATH_SUMMARY + "allData/"

        const val KEY_CONTAINERS = "hydrationContainers"
        const val KEY_UNIT = "hydrationMeasurementUnit"
        const val KEY_AUTO_GOAL = "hydrationAutoGoalEnabled"
        const val UNIT_MILLILITER = "milliliter"
        val DEFAULT_CONTAINERS_ML = listOf(250, 500, 750)

        /** Any number: the widget shows none, and there is no account behind it. */
        const val USER_ID = 1L

        /** The watch's local time, without zero padding, with an optional fraction of the second. */
        val TIMESTAMP_PARSER: DateTimeFormatter = DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('-')
            .appendValue(ChronoField.MONTH_OF_YEAR, 1, 2, SignStyle.NOT_NEGATIVE)
            .appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NOT_NEGATIVE)
            .appendLiteral('T')
            .appendValue(ChronoField.HOUR_OF_DAY, 1, 2, SignStyle.NOT_NEGATIVE)
            .appendLiteral(':')
            .appendValue(ChronoField.MINUTE_OF_HOUR, 1, 2, SignStyle.NOT_NEGATIVE)
            .appendLiteral(':')
            .appendValue(ChronoField.SECOND_OF_MINUTE, 1, 2, SignStyle.NOT_NEGATIVE)
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter(Locale.ROOT)

        val TIMESTAMP_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.S", Locale.ROOT)
    }
}
