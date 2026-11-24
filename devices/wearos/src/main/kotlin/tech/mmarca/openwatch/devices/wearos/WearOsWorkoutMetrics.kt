package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ExerciseLap
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Length
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import tech.mmarca.openwatch.devices.core.saltedRecordId
import tech.mmarca.openwatch.wearlink.WearLinkWorkout
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

/**
 * What a workout's figures ([WorkoutMetrics.Summary], the ones the watch shows)
 * add in Health Connect: the kilometre splits as the session's laps, a line of
 * the session's notes with the heart rate figures, and a VO2max estimate as a
 * record of its own, which needs its own grant and is left out without it.
 * The record's id follows the workout's start, so a re-pull upserts.
 *
 * Pure, so it is testable without a device.
 */
internal object WearOsWorkoutMetrics {

    const val VO2_PREFIX = "wearos_workout_vo2_"

    val vo2Permission: String = HealthPermission.getWritePermission(Vo2MaxRecord::class)

    /** Health Connect's own range for VO2max. */
    private val VO2_RANGE = 0.0..100.0

    private const val METRES_PER_KM = 1000.0

    /** The workout as the shared figures read it: the link's shape, which the phone pulled it in. */
    fun linkWorkout(workout: WearOsWorkout): WearLinkWorkout = WearLinkWorkout(
        startMillis = workout.start.toEpochMilli(),
        endMillis = workout.end.toEpochMilli(),
        exerciseType = workout.exerciseType,
        steps = workout.steps,
        distanceMetres = workout.distanceMetres,
        ascentDecimetres = workout.ascentMetres?.let { (it * DECIMETRES_PER_METRE).roundToInt() },
        pauses = workout.pauses.map { WearLinkWorkout.Pause(it.start.toEpochMilli(), it.endInclusive.toEpochMilli()) },
        route = workout.route.map {
            WearLinkWorkout.RoutePoint(
                epochMillis = it.time.toEpochMilli(),
                latitudeE7 = (it.latitude * E7).roundToInt(),
                longitudeE7 = (it.longitude * E7).roundToInt(),
                altitudeDecimetres = it.altitudeMetres?.let { metres -> (metres * DECIMETRES_PER_METRE).roundToInt() },
                accuracyDecimetres = it.accuracyMetres?.let { metres -> (metres * DECIMETRES_PER_METRE).roundToInt() },
            )
        },
    )

    /** The splits as laps of a kilometre each, every one inside the session as Health Connect requires. */
    fun laps(workout: WearOsWorkout, summary: WorkoutMetrics.Summary): List<ExerciseLap> = summary.splits
        .map { ExerciseLap(Instant.ofEpochMilli(it.startMillis), Instant.ofEpochMilli(it.endMillis), Length.meters(METRES_PER_KM)) }
        .filter { !it.startTime.isBefore(workout.start) && !it.endTime.isAfter(workout.end) && it.startTime.isBefore(it.endTime) }

    /**
     * One line of plain figures for the session's notes, the way another app shows a session's
     * text: the mean and highest heart rate, the minutes per zone (zone 1 first) and the TRIMP.
     * Null with nothing to say.
     */
    fun notes(summary: WorkoutMetrics.Summary): String? {
        val parts = buildList {
            if (summary.averageBpm != null && summary.maxBpm != null) add("Heart rate avg ${summary.averageBpm}, max ${summary.maxBpm} bpm")
            if (summary.zoneSeconds.any { it > 0 }) add("Zones (min) " + summary.zoneSeconds.joinToString("/") { (it / SECONDS_PER_MINUTE).toString() })
            summary.trimp?.let { add("TRIMP ${it.roundToInt()}") }
        }
        return parts.takeIf { it.isNotEmpty() }?.joinToString(". ")
    }

    /** The workout's VO2max at its end, when the figures hold one in Health Connect's range; [granted] must hold its write grant. */
    fun vo2Record(workout: WearOsWorkout, summary: WorkoutMetrics.Summary, zone: ZoneId, granted: Set<String>, salt: String = ""): Vo2MaxRecord? {
        val vo2 = summary.vo2Max?.takeIf { it in VO2_RANGE && vo2Permission in granted } ?: return null
        return Vo2MaxRecord(
            time = workout.end,
            zoneOffset = zone.rules.getOffset(workout.end),
            vo2MillilitersPerMinuteKilogram = vo2,
            // An estimate from speed and heart rate reserve: none of the tests Health Connect names.
            measurementMethod = Vo2MaxRecord.MEASUREMENT_METHOD_OTHER,
            metadata = Metadata.autoRecorded(
                device = Device(type = Device.TYPE_WATCH),
                clientRecordId = saltedRecordId("$VO2_PREFIX${workout.start.toEpochMilli()}", salt),
            ),
        )
    }

    private const val E7 = 1e7
    private const val DECIMETRES_PER_METRE = 10.0
    private const val SECONDS_PER_MINUTE = 60L
}
