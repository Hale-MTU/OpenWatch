package tech.mmarca.openwatch.activityimport

import androidx.health.connect.client.records.ExerciseSessionRecord
import tech.mmarca.healthcalculations.insights.matchExerciseType

// The activity-type inference an imported file gets, as OpenVitals' manual
// entry form computes it: the same words map to the same exercise type
// (matchExerciseType, from health-calculations).

internal const val MaxActivityDurationMinutes = 7 * 24 * 60L

internal fun inferActivityType(
    routeImport: RouteFileImport,
    currentType: ActivityEntryType,
): ActivityEntryType {
    // The declared sport is asked first, on its own. Matching one string of
    // sport, name and file name once imported a bike ride as a run.
    val declared = routeImport.type?.lowercase()?.trim().orEmpty()
    val guessed = listOfNotNull(routeImport.name, routeImport.fileName)
        .joinToString(separator = " ")
        .lowercase()

    // Only when it names something: FIT's `training` and `fitness equipment`
    // are generic buckets, and then the name is all there is.
    val declaredType = declared.takeIf { it.isNotEmpty() }?.let(::matchExerciseType)
    val declaredNamesIt =
        declaredType != null && declaredType != ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT

    val exerciseType = if (declaredNamesIt) {
        declaredType
    } else {
        matchExerciseType(guessed) ?: declaredType
    }

    val requiresGpsRoute = routeImport.points.isNotEmpty()
    return DefaultActivityEntryTypes
        .firstOrNull { it.exerciseType == exerciseType && (!requiresGpsRoute || it.supportsGpsRoute) }
        ?: currentType.takeIf { !requiresGpsRoute || it.supportsGpsRoute }
        ?: DefaultActivityEntryTypes.first { !requiresGpsRoute || it.supportsGpsRoute }
}
