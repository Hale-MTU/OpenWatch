package tech.mmarca.openwatch.wear.ui.preview

import java.time.Duration
import java.time.LocalDate
import tech.mmarca.openwatch.wear.LinePoint
import tech.mmarca.openwatch.wear.MetricUiState
import tech.mmarca.openwatch.wear.SampleStats
import tech.mmarca.openwatch.wear.features.recording.RecordingUiState
import tech.mmarca.openwatch.wear.SleepStage
import tech.mmarca.openwatch.wear.MetricTile
import tech.mmarca.openwatch.wear.WatchSensors
import tech.mmarca.openwatch.wear.WearUiState
import tech.mmarca.openwatch.wear.features.recording.ActivityType
import tech.mmarca.openwatch.wear.features.recording.WorkoutField
import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * Made-up values for previews and for debug builds, where nothing feeds the
 * screens yet. Never shown in a release build.
 */
object SampleData {
    /** The day the sample week ends on, a Sunday. */
    private val today = LocalDate.of(2026, 11, 1)

    val metrics = mapOf(
        MetricTile.STEPS to MetricUiState(
            current = 6_482.0,
            goal = 10_000.0,
            today = doubles(0, 0, 0, 0, 0, 0, 120, 840, 1_310, 420, 260, 380, 1_150, 540, 310, 752),
            week = doubles(8_210, 11_034, 5_420, 9_876, 12_310, 7_045, 6_482),
        ),
        MetricTile.HEART_RATE to MetricUiState(
            current = 72.0,
            today = doubles(64, 66, 63, 70, 78, 91, 104, 98, 86, 79, 74, 71, 69, 72, 75, 72),
            line = evenLine(64, 66, 63, 70, 78, 91, 104, 98, 86, 79, 74, 71, 69, 72, 75, 72),
            week = doubles(68, 71, 66, 70, 74, 69, 73),
            todayStats = SampleStats(low = 56.0, average = 76.0, high = 118.0),
            zoneTime = listOf(214L, 61L, 22L, 6L, 0L).map(Duration::ofMinutes),
        ),
        MetricTile.DISTANCE to MetricUiState(
            current = 4_730.0,
            today = doubles(0, 0, 0, 0, 0, 0, 90, 610, 960, 300, 190, 280, 840, 390, 230, 840),
            week = doubles(6_010, 8_120, 3_950, 7_240, 9_030, 5_160, 4_730),
        ),
        MetricTile.ACTIVE_CALORIES to MetricUiState(
            current = 312.0,
            goal = 500.0,
            today = doubles(0, 0, 0, 0, 0, 0, 6, 41, 63, 20, 13, 18, 55, 26, 15, 55),
            week = doubles(402, 538, 265, 480, 601, 344, 312),
        ),
        MetricTile.FLOORS to MetricUiState(
            current = 9.0,
            goal = 10.0,
            today = doubles(0, 0, 0, 0, 0, 0, 1, 2, 0, 1, 0, 0, 3, 1, 0, 1),
            week = doubles(12, 8, 5, 11, 14, 7, 9),
        ),
        MetricTile.ELEVATION to MetricUiState(
            current = 86.0,
            today = doubles(0, 0, 0, 0, 0, 0, 4, 21, 9, 6, 0, 0, 28, 9, 0, 9),
            week = doubles(112, 74, 40, 96, 131, 62, 86),
        ),
        MetricTile.BLOOD_OXYGEN to MetricUiState(
            current = 97.0,
            today = doubles(97, 96, 98, 97, 95, 96, 97, 98, 97, 97),
            line = evenLine(97, 96, 98, 97, 95, 96, 97, 98, 97, 97),
            week = doubles(97, 96, 97, 98, 96, 97, 97),
        ),
        MetricTile.VO2_MAX to MetricUiState(
            current = 44.0,
            week = doubles(43, 43, 44, 44, 44, 44, 44),
        ),
        MetricTile.RESTING_HEART_RATE to MetricUiState(
            current = 58.0,
            week = doubles(60, 59, 61, 58, 57, 59, 58),
        ),
        MetricTile.HRV to MetricUiState(
            current = 46.0,
            week = doubles(41, 48, 39, 52, 44, 47, 46),
        ),
        MetricTile.SLEEP to MetricUiState(
            current = 442.0,
            goal = 480.0,
            week = doubles(401, 468, 377, 455, 512, 430, 442),
        ),
        MetricTile.HYDRATION to MetricUiState(
            current = 1_250.0,
            goal = 2_000.0,
            today = doubles(0, 0, 0, 0, 0, 0, 0, 250, 250, 0, 0, 0, 500, 0, 0, 250),
            week = doubles(1_750, 2_250, 1_500, 2_000, 1_250, 1_750, 1_250),
        ),
        MetricTile.WEIGHT to MetricUiState(
            current = 72.4,
            week = listOf(73.1, 72.9, 72.8, 72.6, 72.7, 72.5, 72.4),
        ),
    ).mapValues { (_, state) -> state.copy(weekEnd = today) }

    /** Minutes per stage for a 7:22 night. */
    val sleepStages = mapOf(
        SleepStage.AWAKE to 21.0,
        SleepStage.REM to 94.0,
        SleepStage.LIGHT to 243.0,
        SleepStage.DEEP to 84.0,
    )

    /** The fields each workout shows on a watch with a heart rate sensor, GPS and a barometer. */
    val workoutFields: Map<ActivityType, Set<WorkoutField>> = ActivityType.entries.associateWith { activity ->
        buildSet {
            add(WorkoutField.HEART_RATE)
            if (activity != ActivityType.WORKOUT) {
                add(WorkoutField.DISTANCE)
                add(WorkoutField.ELEVATION)
                add(if (activity == ActivityType.CYCLE) WorkoutField.SPEED else WorkoutField.PACE)
            }
            if (activity != ActivityType.CYCLE && activity != ActivityType.WORKOUT) add(WorkoutField.STEPS)
        }
    }

    /** A morning's entries: a weigh-in, a measured pulse and a blood pressure. */
    val entries = listOf(
        WearLinkEntry.of(System.currentTimeMillis() - 3_600_000, WearLinkEntry.Type.WEIGHT, 72.4),
        WearLinkEntry.of(System.currentTimeMillis() - 1_800_000, WearLinkEntry.Type.HEART_RATE, 64.0, measured = true),
        WearLinkEntry.of(System.currentTimeMillis() - 600_000, WearLinkEntry.Type.BLOOD_PRESSURE, 121.0, 79.0),
    )

    /** A watch with a heart rate sensor, a barometer and GPS, and no single-beat sensor. */
    val sensors = WatchSensors.ALL.copy(heartBeat = false)

    val uiState = WearUiState(
        sensors = sensors,
        metrics = metrics,
        sleepStages = sleepStages,
        entries = entries,
    )

    val recording = RecordingUiState(
        elapsedSeconds = 23 * 60 + 41,
        heartRateBpm = 138,
        distanceMeters = 3_240,
        steps = 3_610,
        elevationGainMeters = 38,
        speedMetersPerSecond = 2.28,
        averageSpeedMetersPerSecond = 2.21,
        averageHeartRateBpm = 131,
    )

    private fun doubles(vararg values: Int): List<Double> = values.map(Int::toDouble)

    /** One run of [values] at even steps in time. */
    private fun evenLine(vararg values: Int): List<List<LinePoint>> =
        listOf(values.mapIndexed { index, value -> LinePoint(index.toFloat() / (values.size - 1), value.toDouble()) })
}
