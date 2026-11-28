package tech.mmarca.openwatch.devices.garmin

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import tech.mmarca.openwatch.core.performance.ApplicationScope
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.data.repository.contract.DeviceStateRepository
import tech.mmarca.openwatch.devices.core.DailyGoal
import tech.mmarca.openwatch.devices.core.SharedGoals
import tech.mmarca.openwatch.devices.core.measure.MeasurementOutcome
import tech.mmarca.openwatch.devices.core.measure.MeasurementSink
import tech.mmarca.openwatch.devices.core.measure.PushedMeasurement
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/**
 * The hydration widget on the phone. What each watch logged is kept by day in its own
 * `device_state`, so it goes with the watch, and answers the widget's totals; each drink is handed
 * to the [MeasurementSink], which keeps it until Health Connect has it ([GarminHydrationRecords]).
 *
 * A subtraction, a negative entry, takes back the newest drinks of its day before it, newest
 * first. Those drinks are handed over again with what is left of them, a higher version each
 * time, so Health Connect's day agrees with the watch's. Days are kept for [KEEP_DAYS].
 *
 * The widget's goal is the daily water goal the apps connected over OpenLink share ([SharedGoals]:
 * set in OpenVitals or on a Wear OS watch's card), or Garmin's two litres while there is none.
 */
@Singleton
class GarminHydrationStore(
    private val state: DeviceStateRepository,
    private val registry: DeviceRegistry,
    private val sink: MeasurementSink,
    private val goals: SharedGoals,
    private val scope: CoroutineScope,
    private val zone: () -> ZoneId,
    private val today: () -> LocalDate,
) {

    @Inject
    constructor(
        state: DeviceStateRepository,
        registry: DeviceRegistry,
        sink: MeasurementSink,
        goals: SharedGoals,
        @ApplicationScope scope: CoroutineScope,
    ) : this(state, registry, sink, goals, scope, ZoneId::systemDefault, LocalDate::now)

    /** One read-modify-write of a day at a time: the held link and a sync may both be answering. */
    private val lock = Any()

    /** The log the watch [deviceId]'s sessions answer its widget from. */
    fun log(deviceId: String): GarminHydrationLog = object : GarminHydrationLog {
        override fun log(entry: GarminHydrationEntry) = keep(deviceId, entry)

        override fun day(date: LocalDate): GarminHydrationDay {
            val entries = synchronized(lock) { entries(deviceId, date) }
            val goal = goals.current()[DailyGoal.WATER_ML]?.toDouble() ?: GarminHydrationDay.DEFAULT_GOAL_MILLILITERS
            return GarminHydrationDay(netDrinks(entries).values.sum(), entries.maxOfOrNull { it.at }, goal)
        }

        override fun settings(): String? = state.get(deviceId, KEY_SETTINGS)

        override fun keepSettings(json: String) = state.put(deviceId, KEY_SETTINGS, json)
    }

    /**
     * Hands over what waits for Health Connect for [device] again: after a sync, so a grant given
     * since lets the drinks in, and the sync can name the one still missing.
     */
    suspend fun retryPending(device: BleSensorDevice): MeasurementOutcome = sink.retryPending(device.asGarmin())

    private fun keep(deviceId: String, entry: GarminHydrationEntry) {
        val changed = synchronized(lock) {
            val date = entry.at.toLocalDate()
            val before = entries(deviceId, date)
            // The watch sends an entry again when it missed the answer: the same entry, kept once.
            if (before.any { it.at == entry.at }) return
            val after = (before + entry).sortedBy { it.at }
            state.put(deviceId, dayKey(date), encode(after))
            prune(deviceId)
            val left = netDrinks(before)
            netDrinks(after).filter { (at, milliliters) -> left[at] != milliliters }
                .map { (at, milliliters) -> drink(at, milliliters, version = after.size.toLong()) }
        }
        if (changed.isEmpty()) return
        val device = registry.devices.firstOrNull { it.id == deviceId } ?: return
        scope.launch { sink.accept(device.asGarmin(), changed) }
    }

    private fun drink(at: LocalDateTime, milliliters: Double, version: Long): PushedMeasurement {
        val instant = at.atZone(zone()).toInstant()
        return PushedMeasurement(
            key = GarminHydrationRecords.KEY_PREFIX + instant.toEpochMilli(),
            payload = GarminHydrationRecords.Drink(
                atMillis = instant.toEpochMilli(),
                offsetSeconds = zone().rules.getOffset(instant).totalSeconds,
                milliliters = milliliters,
                version = version,
            ).encode(),
        )
    }

    private fun entries(deviceId: String, date: LocalDate): List<GarminHydrationEntry> =
        state.get(deviceId, dayKey(date))?.let(::decode).orEmpty()

    private fun prune(deviceId: String) {
        val oldest = today().minusDays(KEEP_DAYS)
        state.keys(deviceId)
            .filter { it.startsWith(KEY_DAY_PREFIX) }
            .filter { key -> dateOf(key)?.isBefore(oldest) ?: true }
            .forEach { state.put(deviceId, it, null) }
    }

    private fun dateOf(key: String): LocalDate? =
        runCatching { LocalDate.parse(key.removePrefix(KEY_DAY_PREFIX)) }.getOrNull()

    private fun encode(entries: List<GarminHydrationEntry>): String = JSONArray().apply {
        entries.forEach { put(JSONObject().put(FIELD_AT, it.at.toString()).put(FIELD_ML, it.milliliters)) }
    }.toString()

    private fun decode(text: String): List<GarminHydrationEntry> = try {
        val array = JSONArray(text)
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            runCatching { GarminHydrationEntry(LocalDateTime.parse(item.getString(FIELD_AT)), item.getDouble(FIELD_ML)) }.getOrNull()
        }
    } catch (_: JSONException) {
        emptyList()
    }

    companion object {
        /** How long a day is kept: the widget asks about today, and a late retry about the days before. */
        const val KEEP_DAYS = 14L

        // Stored: do not rename.
        private const val KEY_DAY_PREFIX = "garmin_hydration_day_"
        private const val KEY_SETTINGS = "garmin_hydration_settings"
        private const val FIELD_AT = "at"
        private const val FIELD_ML = "ml"

        private fun dayKey(date: LocalDate) = KEY_DAY_PREFIX + date

        /**
         * What is left of each drink of a day, by its time, once every subtraction took back the
         * newest drinks before it, newest first. A subtraction past them all takes them to zero.
         */
        internal fun netDrinks(entries: List<GarminHydrationEntry>): Map<LocalDateTime, Double> {
            val left = LinkedHashMap<LocalDateTime, Double>()
            for (entry in entries.sortedBy { it.at }) {
                if (entry.milliliters >= 0) {
                    left[entry.at] = entry.milliliters
                    continue
                }
                var owed = -entry.milliliters
                for (at in left.keys.reversed()) {
                    if (owed <= 0) break
                    val drunk = left.getValue(at)
                    val taken = minOf(drunk, owed)
                    left[at] = drunk - taken
                    owed -= taken
                }
            }
            return left
        }

        /**
         * The sink finds a device's mapping by its family's name, and a watch stored before the
         * family was recorded has none. It is a Garmin ([isGarmin]), so its drinks are written as one.
         */
        private fun BleSensorDevice.asGarmin(): BleSensorDevice =
            if (integration == null) copy(integration = DeviceIntegration.GARMIN) else this
    }
}
