package tech.mmarca.openwatch.activityimport

import java.time.Instant
import tech.mmarca.openwatch.core.geo.haversineMeters
import tech.mmarca.openwatch.domain.insights.RouteElevation
import tech.mmarca.openwatch.domain.model.ExerciseRoutePoint

internal data class RouteFileMetadata(
    val name: String?,
    val description: String?,
    val type: String?,
)

internal data class MutableRoutePoint(
    val latitude: Double?,
    val longitude: Double?,
    var elevationMeters: Double? = null,
    var time: Instant? = null,
)

internal fun buildRouteImport(
    fileName: String?,
    points: List<ExerciseRoutePoint>,
    metadata: RouteFileMetadata,
    hasRecordedTimestamps: Boolean = true,
    hasImportedTimeRange: Boolean = true,
): RouteFileImport {
    val sortedPoints = points
        .sortedBy { it.time }
        .distinctBy { it.time }
    require(sortedPoints.size >= MinRoutePoints) {
        "Route must contain at least $MinRoutePoints unique location points."
    }
    return RouteFileImport(
        fileName = fileName,
        points = sortedPoints,
        distanceMeters = routeDistanceMeters(sortedPoints),
        elevationGainedMeters = routeElevationGainMeters(sortedPoints),
        startTime = sortedPoints.first().time,
        endTime = sortedPoints.last().time,
        name = metadata.name,
        description = metadata.description,
        type = metadata.type,
        hasRecordedTimestamps = hasRecordedTimestamps,
        hasImportedTimeRange = hasImportedTimeRange,
    )
}

internal fun String.toInstantOrNull(): Instant? =
    runCatching { Instant.parse(this) }
        .recoverCatching { java.time.OffsetDateTime.parse(this).toInstant() }
        .getOrNull()

internal fun List<MutableRoutePoint>.toRoutePoints(): List<ExerciseRoutePoint> =
    mapNotNull { point ->
        val time = point.time ?: return@mapNotNull null
        val latitude = point.latitude?.takeIf { it in MinLatitude..MaxLatitude } ?: return@mapNotNull null
        val longitude = point.longitude?.takeIf { it in MinLongitude..MaxLongitude } ?: return@mapNotNull null
        ExerciseRoutePoint(
            time = time,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = point.elevationMeters,
            horizontalAccuracyMeters = null,
            verticalAccuracyMeters = null,
        )
    }

internal fun String?.hasExtension(extension: String): Boolean =
    this?.substringAfterLast('.', missingDelimiterValue = "")
        ?.equals(extension, ignoreCase = true) == true

internal fun routeDistanceMeters(points: List<ExerciseRoutePoint>): Double =
    points.zipWithNext().sumOf { (start, end) ->
        haversineMeters(
            startLatitude = start.latitude,
            startLongitude = start.longitude,
            endLatitude = end.latitude,
            endLongitude = end.longitude,
        )
    }

/** Through the smoothing filter, as a recording is; a raw sum banks GPS noise. */
internal fun routeElevationGainMeters(points: List<ExerciseRoutePoint>): Double =
    RouteElevation.routeElevationGain(points)

internal const val MinRoutePoints = 2
internal const val MaxRouteFileBytes = 15 * 1024 * 1024
internal const val MinLatitude = -90.0
internal const val MaxLatitude = 90.0
internal const val MinLongitude = -180.0
internal const val MaxLongitude = 180.0
