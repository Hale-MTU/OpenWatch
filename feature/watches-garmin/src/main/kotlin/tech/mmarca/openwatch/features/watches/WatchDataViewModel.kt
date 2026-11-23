package tech.mmarca.openwatch.features.watches

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.presentation.toScreenError
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.data.repository.contract.GarminWellnessRepository
import tech.mmarca.openwatch.domain.model.GarminWellnessMetric
import tech.mmarca.openwatch.navigation.WatchData

/** One stored watch metric, resolved to a value and the instant it was measured. */
@Immutable
data class WatchMetricReading(
    val value: Long,
    val time: Instant,
)

/**
 * What the app holds that Health Connect cannot: the latest value of each
 * watch-only metric, plus the day series for the dense ones. A metric the
 * watch never sent is absent, so the screen omits it.
 */
@Immutable
data class WatchMetrics(
    val latest: Map<GarminWellnessMetric, WatchMetricReading> = emptyMap(),
    /** Today's series, oldest first, for the metrics dense enough to draw. */
    val stressToday: List<WatchMetricReading> = emptyList(),
    val bodyEnergyToday: List<WatchMetricReading> = emptyList(),
    /**
     * Intensity minutes this week, vigorous counted double as Garmin does.
     * A sum of daily finals: the watch's running total resets nightly.
     */
    val intensityMinutesWeek: Long? = null,
) {
    val isEmpty: Boolean get() = latest.isEmpty()

    operator fun get(metric: GarminWellnessMetric): WatchMetricReading? = latest[metric]

    fun valueOf(metric: GarminWellnessMetric): Long? = latest[metric]?.value

    /** The metrics this watch never sent, named once at the foot of the screen. */
    fun missingFrom(expected: List<GarminWellnessMetric>): List<GarminWellnessMetric> =
        expected.filterNot { it in latest }
}

/**
 * Loads [WatchMetrics]: today's series over the local day, and the weekly
 * total over the Monday-anchored week from each day's final reading.
 */
internal suspend fun loadWatchMetrics(
    repository: GarminWellnessRepository,
    clock: Clock = Clock.systemDefaultZone(),
    /** The watch's id salt: its samples, not another Garmin's. */
    salt: String = "",
): WatchMetrics {
    val zone: ZoneId = clock.zone

    val latest = buildMap {
        for (metric in GarminWellnessMetric.entries) {
            val sample = repository.latest(metric, salt) ?: continue
            put(metric, WatchMetricReading(value = sample.value, time = sample.time))
        }
    }

    val today = LocalDate.now(clock)
    val dayStart = today.atStartOfDay(zone).toInstant()
    val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant()

    suspend fun series(metric: GarminWellnessMetric): List<WatchMetricReading> =
        repository.samplesBetween(metric, dayStart, dayEnd, salt)
            .map { WatchMetricReading(value = it.value, time = it.time) }

    // Rows arrive oldest first, so the last value of a day is its final total.
    // Keyed by local day.
    val weekStart = today.with(DayOfWeek.MONDAY)
    suspend fun dailyFinalsSum(metric: GarminWellnessMetric): Long {
        val rows = repository.samplesBetween(
            metric,
            weekStart.atStartOfDay(zone).toInstant(),
            weekStart.plusDays(7).atStartOfDay(zone).toInstant(),
            salt,
        )
        val finalByDay = LinkedHashMap<LocalDate, Long>()
        for (row in rows) {
            finalByDay[row.time.atZone(zone).toLocalDate()] = row.value
        }
        return finalByDay.values.sum()
    }

    val intensityMinutesWeek = if (
        GarminWellnessMetric.MODERATE_MINUTES in latest ||
        GarminWellnessMetric.VIGOROUS_MINUTES in latest
    ) {
        // Garmin's convention: vigorous minutes count double.
        dailyFinalsSum(GarminWellnessMetric.MODERATE_MINUTES) +
            2 * dailyFinalsSum(GarminWellnessMetric.VIGOROUS_MINUTES)
    } else {
        null
    }

    return WatchMetrics(
        latest = latest,
        stressToday = series(GarminWellnessMetric.STRESS),
        bodyEnergyToday = series(GarminWellnessMetric.BODY_ENERGY),
        intensityMinutesWeek = intensityMinutesWeek,
    )
}

data class WatchDataUiState(
    val isLoading: Boolean = true,
    /** A read that failed, shown as such: never as a watch that sent nothing. */
    val error: ScreenError? = null,
    val metrics: WatchMetrics = WatchMetrics(),
)

/** Loads the watch-only metrics of the route's watch for the watch-data screen. */
@HiltViewModel
class WatchDataViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val wellnessRepository: GarminWellnessRepository,
    private val deviceRepository: BleDeviceRepository,
) : ViewModel() {

    private val deviceId: String = savedStateHandle.toRoute<WatchData>().deviceId

    private val _uiState = MutableStateFlow(WatchDataUiState())
    val uiState: StateFlow<WatchDataUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.value = try {
                val salt = deviceRepository.devices.firstOrNull { it.id == deviceId }?.idSalt.orEmpty()
                WatchDataUiState(isLoading = false, metrics = loadWatchMetrics(wellnessRepository, salt = salt))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                WatchDataUiState(isLoading = false, error = error.toScreenError(logTag = "WatchData"))
            }
        }
    }
}
