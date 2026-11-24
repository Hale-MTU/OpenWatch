package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.Record
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.data.repository.HealthRecordStore
import tech.mmarca.openwatch.wearlink.WorkoutMetrics

/**
 * Writes the watch's finished workouts to Health Connect ([WearOsWorkoutImport]),
 * a page at a time from the phone's cursor ([WearOsSyncCursorStore.workoutCursor]).
 * The cursor moves after each workout is written, so a sync that dies mid-way
 * resumes there, and a workout written twice upserts. Ported from OpenVitals,
 * which writes through its own activity store; OpenWatch writes the records.
 *
 * Without the write grants nothing is written and the cursor stays: the
 * workouts wait on the watch (it keeps a month). That never fails the sync.
 *
 * A workout's figures ([summarize], the watch's own: [WorkoutMetrics]) give
 * the session its laps and its notes, and a VO2max record where its grant is
 * in ([WearOsWorkoutMetrics]). They are optional: a workout whose figures
 * cannot be worked out is written without them.
 */
internal class WearOsWorkoutSync(
    private val importRepository: HealthRecordStore,
    private val cursors: WearOsSyncCursorStore,
    private val granted: suspend () -> Set<String>,
    private val zone: () -> ZoneId,
    /** The workout's figures for the watch with the given id salt; null when there are none. */
    private val summarize: suspend (WearOsWorkout, String) -> WorkoutMetrics.Summary? = { _, _ -> null },
) {

    /** [written] sessions, and the grants that [withheld] some workouts. */
    class Outcome(val written: Int, val withheld: Set<String>)

    /** Null when [pullPage] has no bonded watch to ask. A failure other than a missing grant throws. */
    suspend fun sync(
        deviceId: String,
        /** The watch's id salt, for the workouts' record ids. */
        salt: String = "",
        afterWritten: suspend (WearOsWorkout) -> Unit = {},
        pullPage: suspend (Instant) -> WearOsWorkoutPage?,
    ): Outcome? {
        var since = cursors.workoutCursor(deviceId)
        var written = 0
        var pages = 0
        while (true) {
            val page = pullPage(since) ?: return null
            if (page.workouts.isEmpty()) break
            for (workout in page.workouts) {
                val allowed = granted()
                val missing = WearOsWorkoutImport.permissions(workout) - allowed
                if (missing.isNotEmpty()) return Outcome(written, missing)
                try {
                    importRepository.insertImportedRecords(records(workout, salt, allowed))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (error !is SecurityException && !error.isPermissionFailure()) throw error
                    return Outcome(written, WearOsWorkoutImport.permissions(workout))
                }
                written++
                // Optional, as the walking energy: it never holds the cursor back.
                afterWritten(workout)
                since = workout.start
                cursors.setWorkoutCursor(deviceId, since)
            }
            if (!page.hasMore) break
            if (++pages >= MAX_PAGES) break
        }
        return Outcome(written, emptySet())
    }

    /** The session, its distance, and what its figures add where they can be worked out. */
    private suspend fun records(workout: WearOsWorkout, salt: String, allowed: Set<String>): List<Record> {
        val summary = figures(workout, salt) ?: return WearOsWorkoutImport.records(workout, zone(), salt)
        val session = WearOsWorkoutImport.records(
            workout, zone(), salt, laps = WearOsWorkoutMetrics.laps(workout, summary), notes = WearOsWorkoutMetrics.notes(summary),
        )
        return session + listOfNotNull(WearOsWorkoutMetrics.vo2Record(workout, summary, zone(), allowed, salt))
    }

    /** The workout's figures; null when a read for them failed, which never holds the workout back. */
    private suspend fun figures(workout: WearOsWorkout, salt: String): WorkoutMetrics.Summary? = try {
        summarize(workout, salt)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        WearOsLog.log("workout figures: not worked out: ${error.javaClass.simpleName}")
        null
    }

    private companion object {
        /** A safety net against a watch that always says "more", as the other pulls have. */
        const val MAX_PAGES = 200
    }
}
