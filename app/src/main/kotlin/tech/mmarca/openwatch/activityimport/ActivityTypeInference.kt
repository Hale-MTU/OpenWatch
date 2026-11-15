package tech.mmarca.openwatch.activityimport

import androidx.health.connect.client.records.ExerciseSessionRecord
import kotlin.math.roundToInt
import tech.mmarca.healthcalculations.insights.matchExerciseType

// The activity-type inference and the calorie estimate an imported file gets,
// as OpenVitals' manual entry form computes them: the same words map to the
// same exercise type (matchExerciseType, from health-calculations), and an
// estimate here equals one made there.

internal const val MaxActivityDurationMinutes = 7 * 24 * 60L
internal const val DefaultCalorieEstimateWeightKg = 70.0
internal const val RestingMet = 1.0
internal const val RunningKcalPerKgKm = 1.0
internal const val WalkingKcalPerKgKm = 0.55

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

internal data class ActivityCalorieEstimate(
    val activeCaloriesText: String,
    val totalCaloriesText: String,
)

internal fun activityCalorieEstimate(
    activityType: ActivityEntryType,
    distanceMeters: Double?,
    durationMinutesText: String,
): ActivityCalorieEstimate? {
    if (!activityType.supportsGpsRoute) return null
    val durationMinutes = durationMinutesText.trim().toLongOrNull()
        ?.takeIf { it in 1..MaxActivityDurationMinutes }
        ?: return null
    val hours = durationMinutes / 60.0
    val met = activityMet(activityType.exerciseType) ?: return null
    val restingCalories = DefaultCalorieEstimateWeightKg * hours * RestingMet
    val activeByMet = (met - RestingMet)
        .coerceAtLeast(0.0) * DefaultCalorieEstimateWeightKg * hours
    val activeByDistance = distanceBasedActiveCalories(
        exerciseType = activityType.exerciseType,
        distanceMeters = distanceMeters,
    ) ?: 0.0
    val activeCalories = maxOf(activeByMet, activeByDistance).takeIf { it > 0.0 } ?: return null

    return ActivityCalorieEstimate(
        activeCaloriesText = activeCalories.toCaloriesInputText(),
        totalCaloriesText = (activeCalories + restingCalories).toCaloriesInputText(),
    )
}

internal fun activityMet(exerciseType: Int): Double? =
    when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> 9.8
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> 7.5
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> 3.5
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> 6.0
        ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR -> 4.0
        ExerciseSessionRecord.EXERCISE_TYPE_ROWING,
        ExerciseSessionRecord.EXERCISE_TYPE_PADDLING -> 7.0
        ExerciseSessionRecord.EXERCISE_TYPE_SKIING -> 7.0
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING -> 5.3
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING -> 8.0
        ExerciseSessionRecord.EXERCISE_TYPE_SKATING,
        ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING -> 7.0
        ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING -> 8.0
        ExerciseSessionRecord.EXERCISE_TYPE_SAILING -> 3.0
        ExerciseSessionRecord.EXERCISE_TYPE_SURFING -> 3.0
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> 8.0
        ExerciseSessionRecord.EXERCISE_TYPE_GOLF -> 4.8
        else -> null
    }

internal fun distanceBasedActiveCalories(
    exerciseType: Int,
    distanceMeters: Double?,
): Double? {
    val distanceKm = distanceMeters?.takeIf { it > 0.0 }?.div(1000.0) ?: return null
    val kcalPerKgKm = when (exerciseType) {
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING,
        ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING -> RunningKcalPerKgKm
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING,
        ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR -> WalkingKcalPerKgKm
        else -> return null
    }
    return DefaultCalorieEstimateWeightKg * distanceKm * kcalPerKgKm
}

internal fun Double.toCaloriesInputText(): String =
    roundToInt()
        .coerceAtLeast(1)
        .toString()
