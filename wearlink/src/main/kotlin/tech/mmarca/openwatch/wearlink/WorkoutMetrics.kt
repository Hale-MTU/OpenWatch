package tech.mmarca.openwatch.wearlink

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt
import tech.mmarca.healthcalculations.insights.CardioLoadTimeWindow
import tech.mmarca.healthcalculations.insights.HeartRateRecoveryQuality
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.healthcalculations.insights.WorkoutEnergyResult
import tech.mmarca.healthcalculations.insights.buildActivitySplits
import tech.mmarca.healthcalculations.insights.calculateHeartRateRecovery
import tech.mmarca.healthcalculations.insights.estimateRouteVo2Max
import tech.mmarca.healthcalculations.insights.estimateWorkoutEnergy
import tech.mmarca.healthcalculations.insights.timeWeightedMeanHeartRate
import tech.mmarca.healthcalculations.insights.workoutTrimp
import tech.mmarca.healthcalculations.model.ExerciseData
import tech.mmarca.healthcalculations.model.ExerciseRoutePoint
import tech.mmarca.healthcalculations.model.ExerciseSegment
import tech.mmarca.healthcalculations.model.ExerciseSegmentData
import tech.mmarca.healthcalculations.model.HeartRateSample
import tech.mmarca.healthcalculations.preferences.BodyProfile

/**
 * What a workout comes to, from its heart rate, its route and the body
 * profile: the figures the watch's summary shows and the phone writes to
 * Health Connect, so both give one number. Every figure is
 * health-calculations' (the same OpenVitals shows); this object only hands
 * the watch's workout to it. The workout's active time is its session less
 * its pauses ([activeWindows]): a pause counts for nothing in any figure.
 * The pace, distance over active time, is the one figure worked out here.
 */
object WorkoutMetrics {

    /** One heart rate reading. */
    data class Beat(val epochMillis: Long, val bpm: Int)

    /** One split: the time the kilometre [index] + 1 took, from its start to its end, and without its pauses. */
    data class Split(val index: Int, val startMillis: Long, val endMillis: Long, val activeSeconds: Long)

    data class Summary(
        /** The time-weighted mean ([timeWeightedMeanHeartRate]) over the active time. */
        val averageBpm: Int?,
        val maxBpm: Int?,
        /** Seconds in each of the five zones, zone 1 first ([HeartRateZones.timeInZones]); none without a basis. */
        val zoneSeconds: List<Long>,
        /** Banister's TRIMP ([workoutTrimp]); null without a resting rate in the basis. */
        val trimp: Double?,
        /** Energy above rest, kcal ([energy]). Null without an estimate: no weight, or no sourced cost. */
        val activeKcal: Double?,
        /** Whole energy, kcal: [activeKcal] and the resting share of the same minutes. */
        val totalKcal: Double?,
        /** One per whole kilometre of the route ([splits]). */
        val splits: List<Split>,
        /** Seconds per kilometre over the active time; null without a distance. */
        val paceSecondsPerKm: Double?,
        /** ml/kg/min ([estimateRouteVo2Max]); null for a sport or a session it does not hold for. */
        val vo2Max: Double?,
        /** The heart rate's recovery after the stop ([recovery]); null when it was not measured. */
        val recovery: Recovery? = null,
    )

    /**
     * The heart rate's fall one and two minutes after the stop, from the peak of the
     * seconds before it ([peakBpm]), as health-calculations reads a recovery
     * ([calculateHeartRateRecovery]). A reading it judges misleading
     * ([HeartRateRecoveryQuality.INVALID]: the heart rate was already falling before the
     * stop, or did not fall after it) carries no fall.
     */
    data class Recovery(val oneMinuteBpm: Int?, val twoMinutesBpm: Int?, val peakBpm: Int? = null)

    /** [profile] is the wearer's body profile; [day] the day its age is read on, the workout's own. */
    fun summarize(
        workout: WearLinkWorkout,
        beats: List<Beat>,
        zones: HeartRateZones.Basis?,
        profile: BodyProfile,
        day: LocalDate,
    ): Summary {
        val windows = activeWindows(workout)
        val samples = samples(beats, workout)
        val estimate = energy(workout, beats, profile, day) as? WorkoutEnergyResult.Estimate
        val resting = zones?.restingBpm?.toLong()
        val max = zones?.maxBpm?.toLong()
        val distance = workout.distanceMetres
        val activeSeconds = windows.sumOf { Duration.between(it.start, it.end).seconds }.toDouble()
        return Summary(
            averageBpm = timeWeightedMeanHeartRate(samples.map { it.time to it.beatsPerMinute }, windows)?.roundToInt(),
            maxBpm = samples.maxOfOrNull { it.beatsPerMinute.toInt() },
            zoneSeconds = zones?.let { basis -> HeartRateZones.timeInZones(samples, basis.boundsBpm, windows).map { it.seconds } }
                ?: List(HeartRateZones.COUNT) { 0L },
            trimp = if (resting != null && max != null) workoutTrimp(samples, resting, max, windows, profile.sex) else null,
            activeKcal = estimate?.activeKcal,
            totalKcal = estimate?.totalKcal,
            splits = splits(workout),
            paceSecondsPerKm = if (distance != null && distance > 0 && activeSeconds > 0) activeSeconds / (distance / METRES_PER_KM) else null,
            vo2Max = if (resting != null && max != null) {
                estimateRouteVo2Max(workout.exerciseType, routePoints(workout), samples, windows, resting, max)
            } else {
                null
            },
        )
    }

    /** The session less its pauses: the time every figure counts. */
    fun activeWindows(workout: WearLinkWorkout): List<CardioLoadTimeWindow> {
        val windows = ArrayList<CardioLoadTimeWindow>()
        var from = workout.startMillis
        for (pause in workout.pauses.sortedBy { it.startMillis }) {
            if (pause.startMillis > from) windows += window(from, minOf(pause.startMillis, workout.endMillis))
            from = maxOf(from, pause.endMillis)
        }
        if (workout.endMillis > from) windows += window(from, workout.endMillis)
        return windows
    }

    /**
     * The workout's calories, by health-calculations' one estimate
     * ([estimateWorkoutEnergy], the same OpenVitals uses): Keytel from the
     * time-weighted mean heart rate of the active time, else ACSM from a walk or
     * run's distance, else the activity's Compendium MET, over the active time.
     */
    fun energy(workout: WearLinkWorkout, beats: List<Beat>, profile: BodyProfile, day: LocalDate): WorkoutEnergyResult {
        val windows = activeWindows(workout)
        return estimateWorkoutEnergy(
            exerciseType = workout.exerciseType,
            durationMinutes = windows.sumOf { Duration.between(it.start, it.end).toMillis() } / MINUTE_MILLIS,
            meanHeartRateBpm = timeWeightedMeanHeartRate(samples(beats, workout).map { it.time to it.beatsPerMinute }, windows),
            profile = profile,
            today = day,
            distanceMeters = workout.distanceMetres?.toDouble()?.takeIf { it > 0.0 },
        )
    }

    /**
     * One split per whole kilometre of the route, its time without the pauses in it: the
     * route's splits as health-calculations cuts them ([buildActivitySplits]), each kilometre
     * mark timed in proportion inside the stretch it falls in. The shorter rest after the last
     * whole kilometre is no split here.
     */
    fun splits(workout: WearLinkWorkout): List<Split> =
        buildActivitySplits(
            workout = exerciseData(workout),
            routePoints = routePoints(workout),
            speedSamples = emptyList(),
            heartRateSamples = emptyList(),
            splitDistanceMeters = METRES_PER_KM,
        ).splits.filterNot { it.isPartial }.mapIndexed { index, split ->
            Split(index, split.startTime.toEpochMilli(), split.endTime.toEpochMilli(), split.elapsedMs / MILLIS_PER_SECOND)
        }

    /**
     * The recovery from [stopMillis], by [calculateHeartRateRecovery]: the falls from the peak
     * of the last seconds before the stop to the readings one and two minutes after it. Whether
     * the effort was near-maximal is judged against [profile]'s entered maximum, else the one its
     * age on [day] predicts, with [restingBpm]; the falls and the peak do not depend on them, so
     * a workout that only asks whether a rest holds a recovery passes none.
     */
    fun recovery(
        beats: List<Beat>,
        stopMillis: Long,
        profile: BodyProfile = BodyProfile(),
        restingBpm: Int? = null,
        day: LocalDate? = null,
    ): Recovery {
        val reading = calculateHeartRateRecovery(
            recoveryStart = Instant.ofEpochMilli(stopMillis),
            samples = beats.map { it.sample() },
            restingHeartRateBpm = restingBpm,
            ageYears = day?.let(profile::ageYears),
            observedMaxHeartRateBpm = null,
            explicitMaxHeartRateBpm = profile.maxHeartRateBpm,
            sex = profile.sex,
        )
        val measured = reading.quality != HeartRateRecoveryQuality.INVALID
        fun drop(minutes: Long) = reading.markAt(Duration.ofMinutes(minutes))?.dropBpm?.toInt()?.takeIf { measured }
        return Recovery(drop(1), drop(2), reading.peakBpm?.toInt())
    }

    /**
     * The fewest readings in the two minutes after a stop that show a recovery was measured: one
     * a second while the watch read the heart rate live, rather than the day's one in ten
     * seconds, on which the marks (a reading within five seconds of each) are a matter of luck.
     */
    const val MIN_RECOVERY_READINGS: Int = 60

    /** The readings inside the session and outside its pauses. */
    private fun samples(beats: List<Beat>, workout: WearLinkWorkout): List<HeartRateSample> = beats
        .filter { it.epochMillis in workout.startMillis..workout.endMillis }
        .filter { beat -> workout.pauses.none { beat.epochMillis in it.startMillis until it.endMillis } }
        .map { it.sample() }

    private fun Beat.sample() = HeartRateSample(Instant.ofEpochMilli(epochMillis), bpm.toLong(), source = "")

    private fun routePoints(workout: WearLinkWorkout): List<ExerciseRoutePoint> = workout.route
        .sortedBy { it.epochMillis }
        .map {
            ExerciseRoutePoint(
                time = Instant.ofEpochMilli(it.epochMillis),
                latitude = it.latitudeE7 / E7,
                longitude = it.longitudeE7 / E7,
                altitudeMeters = it.altitudeDecimetres?.div(DECIMETRES_PER_METRE),
                horizontalAccuracyMeters = it.accuracyDecimetres?.div(DECIMETRES_PER_METRE),
                verticalAccuracyMeters = null,
            )
        }

    /** The session as health-calculations' splits read it: its type, its span, its distance and its pauses. */
    private fun exerciseData(workout: WearLinkWorkout) = ExerciseData(
        id = "",
        title = null,
        exerciseType = workout.exerciseType,
        startTime = Instant.ofEpochMilli(workout.startMillis),
        endTime = Instant.ofEpochMilli(workout.endMillis),
        durationMs = workout.endMillis - workout.startMillis,
        source = "",
        totalDistanceMeters = workout.distanceMetres?.toDouble(),
        segments = workout.pauses.map {
            ExerciseSegmentData(
                startTime = Instant.ofEpochMilli(it.startMillis),
                endTime = Instant.ofEpochMilli(it.endMillis),
                segmentType = ExerciseSegment.EXERCISE_SEGMENT_TYPE_PAUSE,
                repetitions = 0,
            )
        },
    )

    private fun window(fromMillis: Long, toMillis: Long) =
        CardioLoadTimeWindow(Instant.ofEpochMilli(fromMillis), Instant.ofEpochMilli(toMillis))

    private const val METRES_PER_KM = 1000.0
    private const val MINUTE_MILLIS = 60_000.0
    private const val MILLIS_PER_SECOND = 1_000L
    private const val E7 = 1e7
    private const val DECIMETRES_PER_METRE = 10.0
}
