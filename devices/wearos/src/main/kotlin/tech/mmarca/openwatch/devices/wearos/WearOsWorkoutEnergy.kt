package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.WeightRecord
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.healthcalculations.insights.WorkoutEnergyResult
import tech.mmarca.healthcalculations.preferences.BodyProfile
import tech.mmarca.openwatch.healthconnect.HealthConnectReads
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

/**
 * Writes a watch workout's active calories ([WearOsWorkoutImport.activeCalories],
 * the estimate the watch's summary shows) once the session is written. The
 * heart rate is the watch's own hour records ([watchBeats]), which the same
 * sync wrote to Health Connect before it pulled the workouts, read as far as
 * the hour record a workout began in reaches back; the age is read on the
 * workout's day, as on the watch. Optional like the walking energy: a missing
 * grant or a failed read never fails the sync.
 */
internal class WearOsWorkoutEnergy(
    private val healthConnect: HealthConnectReads,
    private val profile: suspend () -> BodyProfile,
    private val insert: suspend (List<Record>) -> Unit,
    private val zone: () -> ZoneId,
) {

    sealed interface Outcome {
        data object Written : Outcome
        /** Nothing to write: no sourced cost for the activity, or a read failed. */
        data object Skipped : Outcome
        data object PermissionMissing : Outcome
        /** The profile has no weight; [weightReadWithheld] when Health Connect's was out of reach. */
        data class NoWeight(val weightReadWithheld: Boolean) : Outcome
    }

    suspend fun write(workout: WearOsWorkout, salt: String = ""): Outcome {
        val granted = healthConnect.grantedPermissions()
        if (WearOsWorkoutImport.energyPermission !in granted) {
            WearOsLog.log("workout energy: not written, Health Connect permission missing")
            return Outcome.PermissionMissing
        }
        val body = try {
            profile()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            WearOsLog.log("workout energy: could not read the body profile: $error")
            return Outcome.Skipped
        }
        val (record, result) = WearOsWorkoutImport.activeCalories(
            workout = workout,
            zone = zone(),
            profile = body,
            beats = heartRate(workout, salt),
            day = workout.start.atZone(zone()).toLocalDate(),
            salt = salt,
        )
        if (result is WorkoutEnergyResult.MissingInputs) {
            WearOsLog.log("workout energy: no body weight, so no calories")
            return Outcome.NoWeight(weightReadWithheld = WEIGHT_READ_PERMISSION !in granted)
        }
        record ?: return Outcome.Skipped
        insert(listOf(record))
        return Outcome.Written
    }

    /** The watch's heart rate in the workout's span; empty when it could not be read, and the activity's MET is used. */
    private suspend fun heartRate(workout: WearOsWorkout, salt: String): List<WorkoutMetrics.Beat> = try {
        healthConnect.watchBeats(workout.start, workout.end, salt)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        WearOsLog.log("workout energy: could not read heart rate, using the activity's MET: $error")
        emptyList()
    }

    private companion object {
        val WEIGHT_READ_PERMISSION = HealthPermission.getReadPermission(WeightRecord::class)
    }
}
