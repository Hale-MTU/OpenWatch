package tech.mmarca.openwatch.activityimport

import androidx.health.connect.client.records.ExerciseSessionRecord

// The activity-type inference an imported file gets, as OpenVitals' manual
// entry form computes it: the same words map to the same exercise type.

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
