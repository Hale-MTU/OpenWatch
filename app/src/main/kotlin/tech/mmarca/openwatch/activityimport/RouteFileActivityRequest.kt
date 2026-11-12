package tech.mmarca.openwatch.activityimport

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.roundToInt
import tech.mmarca.openwatch.domain.model.ActivityRecordSource
import tech.mmarca.openwatch.domain.model.ActivityWriteRequest
import tech.mmarca.openwatch.domain.insights.WorkoutEnergyResult
import tech.mmarca.openwatch.domain.insights.estimateWorkoutEnergy
import tech.mmarca.openwatch.domain.model.BleRecordingSampleBuffer
import tech.mmarca.openwatch.domain.preferences.BodyProfile

/**
 * An imported file's write request, and what became of its calorie estimate:
 * null when the file measured calories itself or no body profile was at hand,
 * [WorkoutEnergyResult.MissingInputs] when the profile has no weight, so the
 * caller can say so.
 */
internal data class ImportedActivityRequest(
    val request: ActivityWriteRequest,
    val energyEstimate: WorkoutEnergyResult?,
)

/**
 * The write request for a file imported with nobody to review it: a watch sync, a bulk
 * folder import.
 *
 * These used to be pushed through the entry form: the file became form text and the text
 * was parsed back. The start lost its seconds, the duration was rounded up to a minute,
 * the records were stored as a manual entry from a phone, and every client id was random,
 * so importing a file twice stored the workout twice.
 *
 * Null when the file cannot stand alone as an activity: no time range, a span past the
 * limit, or a route on a type that takes none. A caller that has a form can still fall
 * back to it.
 *
 * A file that measured no calories gets an estimate from [estimateWorkoutEnergy] with the
 * body [profile], as OpenVitals' entry form makes it: the mean heart rate the file
 * recorded, the distance of a walk or run, or the activity's Compendium MET. Without a
 * weight there is no estimate; a null [profile] (it could not be read) makes none either.
 */
internal fun RouteFileImport.toImportWriteRequest(
    fallbackType: ActivityEntryType,
    source: ActivityRecordSource,
    profile: BodyProfile?,
): ImportedActivityRequest? {
    if (!hasImportedTimeRange) return null
    val key = contentKey ?: return null
    val type = inferActivityType(this, fallbackType)
    if (points.isNotEmpty() && !type.supportsGpsRoute) return null

    // The session must contain everything written beside it.
    val firstTimes = listOfNotNull(startTime, points.firstOrNull()?.time, bleSamples.firstSampleTime())
    val lastTimes = listOfNotNull(
        endTime,
        points.lastOrNull()?.time?.plusSeconds(1),
        bleSamples.lastSampleTime()?.plusSeconds(1),
    )
    val start = firstTimes.min()
    val end = lastTimes.max()
    if (!start.isBefore(end)) return null
    val minutes = Duration.between(start, end).toMinutes()
    if (minutes > MaxActivityDurationMinutes) return null

    val active = activeCaloriesKcal?.takeIf { it > 0.0 }
    val total = totalCaloriesKcal?.takeIf { it > 0.0 }
    val samples = bleSamples.within(start, end)
    // As the form does: estimate only for a file that measured no calories at all.
    val energy = if (active == null && total == null && profile != null) {
        estimateWorkoutEnergy(
            exerciseType = type.exerciseType,
            // Whole minutes, as the form counts them, so both give the same number.
            durationMinutes = minutes.coerceAtLeast(1).toDouble(),
            meanHeartRateBpm = samples.heartRateSamples.takeIf { it.isNotEmpty() }
                ?.map { it.beatsPerMinute }?.average(),
            profile = profile,
            // The age on the workout's day, not on the day of the import.
            today = start.atZone(ZoneId.systemDefault()).toLocalDate(),
            distanceMeters = distanceMeters.takeIf { it > 0.0 },
        )
    } else {
        null
    }
    val estimate = energy as? WorkoutEnergyResult.Estimate
    val activeKcal = active ?: estimate?.activeKcal?.toWholeKcal()
    val totalKcal = total ?: estimate?.totalKcal?.toWholeKcal()

    val request = ActivityWriteRequest(
        exerciseType = type.exerciseType,
        startTime = start,
        endTime = end,
        title = name ?: fileName?.substringBeforeLast('.', missingDelimiterValue = fileName) ?: type.defaultTitle,
        notes = description?.trim()?.takeIf { it.isNotEmpty() },
        routePoints = points,
        distanceMeters = distanceMeters.takeIf { type.supportsDistance && it > 0.0 },
        elevationGainedMeters = elevationGainedMeters.takeIf { type.supportsElevation && it > 0.0 },
        // Health Connect refuses a total below the active part.
        activeCaloriesKcal = activeKcal?.takeIf { totalKcal == null || it <= totalKcal },
        totalCaloriesKcal = totalKcal,
        bleSamples = samples,
        source = source,
        importKey = key,
    )
    return ImportedActivityRequest(request, energy)
}

/** Whole kilocalories, at least 1, as the entry form stores an estimate. */
private fun Double.toWholeKcal(): Double = roundToInt().coerceAtLeast(1).toDouble()

private fun BleRecordingSampleBuffer.within(start: Instant, end: Instant): BleRecordingSampleBuffer {
    fun Instant.isInside(): Boolean = !isBefore(start) && isBefore(end)
    return BleRecordingSampleBuffer(
        heartRateSamples = heartRateSamples.filter { it.time.isInside() },
        powerSamples = powerSamples.filter { it.time.isInside() },
        cyclingCadenceSamples = cyclingCadenceSamples.filter { it.time.isInside() },
        speedSamples = speedSamples.filter { it.time.isInside() },
        stepsCadenceSamples = stepsCadenceSamples.filter { it.time.isInside() },
    )
}
