package tech.mmarca.openwatch.activityimport

import androidx.health.connect.client.records.ExerciseSessionRecord
import kotlin.math.roundToInt

// The activity-type inference and the calorie estimate an imported file gets,
// as OpenVitals' manual entry form computes them: the same words map to the
// same exercise type, and an estimate here equals one made there.

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

/**
 * The exercise type [sourceText] names, or null. Every name fitSubSportName and
 * fitPlainSportName emit is matched here. The specific comes before the general
 * it contains: "table tennis" before "tennis", "stair climbing" before "climb".
 */
internal fun matchExerciseType(sourceText: String): Int? =
    when {
        sourceText.containsAny("treadmill") -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL
        sourceText.containsAny("strength", "weight lifting", "weightlifting") -> {
            ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING
        }
        sourceText.containsAny("yoga") -> ExerciseSessionRecord.EXERCISE_TYPE_YOGA
        sourceText.containsAny("pilates") -> ExerciseSessionRecord.EXERCISE_TYPE_PILATES
        sourceText.containsAny("stretch", "flexibility", "mobility") -> ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING
        sourceText.containsAny("breath") -> ExerciseSessionRecord.EXERCISE_TYPE_GUIDED_BREATHING
        sourceText.containsAny("hiit", "interval") -> {
            ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING
        }
        sourceText.containsAny("boot camp", "bootcamp") -> ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP
        sourceText.containsAny("elliptical", "cross trainer") -> ExerciseSessionRecord.EXERCISE_TYPE_ELLIPTICAL
        sourceText.containsAny("stair machine", "stairmaster", "stepper", "step mill") -> {
            ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING_MACHINE
        }
        sourceText.containsAny("stair", "floor climbing") -> ExerciseSessionRecord.EXERCISE_TYPE_STAIR_CLIMBING
        sourceText.containsAny("rowing machine", "indoor rowing") -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING_MACHINE
        sourceText.containsAny("pool", "lap swim") -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL
        sourceText.containsAny("table tennis", "ping pong") -> ExerciseSessionRecord.EXERCISE_TYPE_TABLE_TENNIS
        sourceText.containsAny("tennis") -> ExerciseSessionRecord.EXERCISE_TYPE_TENNIS
        sourceText.containsAny("badminton") -> ExerciseSessionRecord.EXERCISE_TYPE_BADMINTON
        sourceText.containsAny("squash") -> ExerciseSessionRecord.EXERCISE_TYPE_SQUASH
        sourceText.containsAny("racquetball") -> ExerciseSessionRecord.EXERCISE_TYPE_RACQUETBALL
        sourceText.containsAny("basketball") -> ExerciseSessionRecord.EXERCISE_TYPE_BASKETBALL
        sourceText.containsAny("volleyball") -> ExerciseSessionRecord.EXERCISE_TYPE_VOLLEYBALL
        sourceText.containsAny("handball") -> ExerciseSessionRecord.EXERCISE_TYPE_HANDBALL
        sourceText.containsAny("baseball") -> ExerciseSessionRecord.EXERCISE_TYPE_BASEBALL
        sourceText.containsAny("softball") -> ExerciseSessionRecord.EXERCISE_TYPE_SOFTBALL
        sourceText.containsAny("cricket") -> ExerciseSessionRecord.EXERCISE_TYPE_CRICKET
        sourceText.containsAny("rugby") -> ExerciseSessionRecord.EXERCISE_TYPE_RUGBY
        sourceText.containsAny("american football") -> ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AMERICAN
        sourceText.containsAny("australian football", "aussie rules") -> {
            ExerciseSessionRecord.EXERCISE_TYPE_FOOTBALL_AUSTRALIAN
        }
        sourceText.containsAny("soccer", "football") -> ExerciseSessionRecord.EXERCISE_TYPE_SOCCER
        sourceText.containsAny("roller hockey", "inline hockey") -> ExerciseSessionRecord.EXERCISE_TYPE_ROLLER_HOCKEY
        sourceText.containsAny("hockey") -> ExerciseSessionRecord.EXERCISE_TYPE_ICE_HOCKEY
        sourceText.containsAny("water polo") -> ExerciseSessionRecord.EXERCISE_TYPE_WATER_POLO
        sourceText.containsAny("ice skat") -> ExerciseSessionRecord.EXERCISE_TYPE_ICE_SKATING
        sourceText.containsAny("fencing") -> ExerciseSessionRecord.EXERCISE_TYPE_FENCING
        sourceText.containsAny("boxing", "kickbox") -> ExerciseSessionRecord.EXERCISE_TYPE_BOXING
        sourceText.containsAny("martial", "karate", "judo", "taekwondo", "jiu") -> {
            ExerciseSessionRecord.EXERCISE_TYPE_MARTIAL_ARTS
        }
        sourceText.containsAny("danc") -> ExerciseSessionRecord.EXERCISE_TYPE_DANCING
        sourceText.containsAny("gymnastic") -> ExerciseSessionRecord.EXERCISE_TYPE_GYMNASTICS
        sourceText.containsAny("frisbee", "disc golf", "ultimate") -> ExerciseSessionRecord.EXERCISE_TYPE_FRISBEE_DISC
        sourceText.containsAny("paraglid", "hang glid") -> ExerciseSessionRecord.EXERCISE_TYPE_PARAGLIDING
        sourceText.containsAny("scuba", "diving") -> ExerciseSessionRecord.EXERCISE_TYPE_SCUBA_DIVING
        sourceText.containsAny("climb", "boulder") -> ExerciseSessionRecord.EXERCISE_TYPE_ROCK_CLIMBING
        sourceText.containsAny("snowboard") -> ExerciseSessionRecord.EXERCISE_TYPE_SNOWBOARDING
        sourceText.containsAny("snowshoe") -> ExerciseSessionRecord.EXERCISE_TYPE_SNOWSHOEING
        sourceText.containsAny("ski") -> ExerciseSessionRecord.EXERCISE_TYPE_SKIING
        sourceText.containsAny("hike", "hiking") -> ExerciseSessionRecord.EXERCISE_TYPE_HIKING
        sourceText.containsAny("run", "running", "jog") -> ExerciseSessionRecord.EXERCISE_TYPE_RUNNING
        // Before the plain bike: a trainer ride must not land on a map.
        sourceText.containsAny("indoor cycling", "stationary bike", "spin") -> {
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
        }
        sourceText.containsAny("bike", "biking", "bicycle", "cycling", "cycle", "ride") -> {
            ExerciseSessionRecord.EXERCISE_TYPE_BIKING
        }
        sourceText.containsAny("walk", "walking") -> ExerciseSessionRecord.EXERCISE_TYPE_WALKING
        sourceText.containsAny("wheelchair") -> ExerciseSessionRecord.EXERCISE_TYPE_WHEELCHAIR
        sourceText.containsAny("row", "rowing") -> ExerciseSessionRecord.EXERCISE_TYPE_ROWING
        sourceText.containsAny("paddle", "kayak", "canoe") -> ExerciseSessionRecord.EXERCISE_TYPE_PADDLING
        sourceText.containsAny("skate", "skating") -> ExerciseSessionRecord.EXERCISE_TYPE_SKATING
        sourceText.containsAny("sail", "sailing") -> ExerciseSessionRecord.EXERCISE_TYPE_SAILING
        sourceText.containsAny("surf", "surfing") -> ExerciseSessionRecord.EXERCISE_TYPE_SURFING
        sourceText.containsAny("swim", "swimming") -> ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER
        sourceText.containsAny("golf") -> ExerciseSessionRecord.EXERCISE_TYPE_GOLF
        sourceText.containsAny("class") -> ExerciseSessionRecord.EXERCISE_TYPE_EXERCISE_CLASS
        sourceText.containsAny("training", "workout", "fitness") -> ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT
        else -> null
    }

internal fun String.containsAny(vararg values: String): Boolean =
    values.any(::contains)

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
