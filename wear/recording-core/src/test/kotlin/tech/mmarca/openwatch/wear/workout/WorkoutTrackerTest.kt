package tech.mmarca.openwatch.wear.workout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.healthcalculations.insights.HeartRateZones
import tech.mmarca.openwatch.wearlink.WorkoutMetrics
import tech.mmarca.openwatch.wearlink.WorkoutSport
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

class WorkoutTrackerTest {

    /** A minute boundary, so ascent rows line up with the step minutes. */
    private val t0 = 1_700_000_040_000L

    /** A fix [metres] north of the origin, [seconds] after the start. 1e-5 degrees of latitude is 1.112 m. */
    private fun fix(seconds: Int, metres: Double, accuracy: Double? = 5.0, speed: Double? = null) = WorkoutFix(
        epochMillis = t0 + seconds * 1000L,
        latitude = 48.0 + metres / 111_195.0,
        longitude = 11.0,
        altitudeMetres = 500.0,
        accuracyMetres = accuracy,
        speedMetresPerSecond = speed,
    )

    private fun run(sport: WorkoutSport = WorkoutSport.RUNNING) = WorkoutTracker(sport, t0)

    @Test fun `distance adds up along a straight run`() {
        val tracker = run()
        for (s in 0..60) tracker.onFix(fix(s, s * 3.0))

        val snapshot = tracker.snapshot(t0 + 60_000)
        assertEquals(180.0, snapshot.distanceMetres!!.toDouble(), 2.0)
        assertEquals(3.0, snapshot.averageSpeedMetresPerSecond!!, 0.1)
        assertFalse(snapshot.waitingForFix)
    }

    @Test fun `a wearer standing still gains no distance`() {
        val tracker = run()
        for (s in 0..60) tracker.onFix(fix(s, if (s % 2 == 0) 0.0 else 2.0))

        assertEquals(0, tracker.snapshot(t0 + 60_000).distanceMetres)
    }

    @Test fun `slow walking still adds up because the anchor stays until the step is big enough`() {
        val tracker = run(WorkoutSport.WALKING)
        for (s in 0..100) tracker.onFix(fix(s, s * 1.0, accuracy = 3.0))

        assertEquals(100.0, tracker.snapshot(t0 + 100_000).distanceMetres!!.toDouble(), 5.0)
    }

    @Test fun `a fix that is too inaccurate or implies an impossible speed is dropped`() {
        val tracker = run()
        tracker.onFix(fix(0, 0.0))
        tracker.onFix(fix(1, 10.0, accuracy = 80.0))
        tracker.onFix(fix(2, 5_000.0))
        tracker.onFix(fix(3, 6.0))

        assertEquals(6.0, tracker.snapshot(t0 + 3_000).distanceMetres!!.toDouble(), 1.0)
    }

    @Test fun `after a long gap a far jump starts over without adding the jump`() {
        val tracker = run()
        tracker.onFix(fix(0, 0.0))
        tracker.onFix(fix(120, 20_000.0))
        tracker.onFix(fix(122, 20_006.0))

        assertEquals(6.0, tracker.snapshot(t0 + 122_000).distanceMetres!!.toDouble(), 1.0)
    }

    @Test fun `a bike may go faster than a runner`() {
        val bike = run(WorkoutSport.BIKING)
        val runner = run()
        for (tracker in listOf(bike, runner)) {
            tracker.onFix(fix(0, 0.0))
            tracker.onFix(fix(10, 200.0))
        }

        assertEquals(200.0, bike.snapshot(t0 + 10_000).distanceMetres!!.toDouble(), 2.0)
        assertEquals(0, runner.snapshot(t0 + 10_000).distanceMetres)
    }

    @Test fun `a paused workout takes no fixes, and the first one after resuming only sets the start`() {
        val tracker = run()
        tracker.onFix(fix(0, 0.0))
        tracker.onFix(fix(10, 30.0))
        tracker.pause(t0 + 10_000)
        assertNull(tracker.onFix(fix(20, 60.0)))
        tracker.resume(t0 + 60_000)
        tracker.onFix(fix(61, 900.0))
        tracker.onFix(fix(71, 930.0))

        val snapshot = tracker.snapshot(t0 + 71_000)
        assertEquals(60.0, snapshot.distanceMetres!!.toDouble(), 2.0)
        assertEquals(listOf(t0 + 10_000 to t0 + 60_000), tracker.pauseList().map { it.startMillis to it.endMillis })
        assertEquals(21L, snapshot.activeSeconds)
    }

    @Test fun `active time stops during an open pause`() {
        val tracker = run()
        tracker.pause(t0 + 30_000)

        assertEquals(30L, tracker.snapshot(t0 + 90_000).activeSeconds)
        assertTrue(tracker.snapshot(t0 + 90_000).paused)
    }

    @Test fun `route points are thinned to one every five seconds and a few metres`() {
        val tracker = run()
        val kept = (0..60).mapNotNull { tracker.onFix(fix(it, it * 4.0)) }

        assertEquals(13, kept.size)
        assertEquals(kept, tracker.route)
        assertEquals(5000L, kept[1].epochMillis - kept[0].epochMillis)
    }

    @Test fun `a route point carries degrees times ten million, altitude and accuracy in decimetres`() {
        val tracker = run()
        val point = tracker.onFix(fix(0, 0.0, accuracy = 4.5))!!

        assertEquals(480_000_000, point.latitudeE7)
        assertEquals(110_000_000, point.longitudeE7)
        assertEquals(5000, point.altitudeDecimetres)
        assertEquals(45, point.accuracyDecimetres)
    }

    @Test fun `a sport without GPS has no distance, route or wait for a fix`() {
        val tracker = WorkoutTracker(WorkoutSport.YOGA, t0)

        assertNull(tracker.onFix(fix(0, 0.0)))
        val snapshot = tracker.snapshot(t0 + 5_000)
        assertNull(snapshot.distanceMetres)
        assertFalse(snapshot.waitingForFix)
        assertNull(tracker.finish(t0 + 60_000).distanceMetres)
    }

    @Test fun `a GPS sport waits for its first fix, and declined location counts as none`() {
        assertTrue(run().snapshot(t0 + 1_000).waitingForFix)
        val declined = WorkoutTracker(WorkoutSport.RUNNING, t0, useGps = false)
        assertFalse(declined.snapshot(t0 + 1_000).waitingForFix)
        assertNull(declined.onFix(fix(0, 0.0)))
    }

    @Test fun `steps count from the first reading, and cadence follows`() {
        val tracker = run()
        tracker.onStepTotal(t0, 10_000)
        tracker.onStepTotal(t0 + 30_000, 10_090)
        tracker.onStepTotal(t0 + 60_000, 10_170)

        val snapshot = tracker.snapshot(t0 + 60_000)
        assertEquals(170, snapshot.steps)
        assertEquals(170, snapshot.cadenceStepsPerMinute)
    }

    @Test fun `a snapshot at the very start of a sport on foot has no cadence and does not throw`() {
        val tracker = run()

        val snapshot = tracker.snapshot(t0)

        assertNull(snapshot.cadenceStepsPerMinute)
        assertEquals(0L, snapshot.activeSeconds)
    }

    @Test fun `a sport that is not on foot reports no steps`() {
        val tracker = run(WorkoutSport.BIKING)
        tracker.onStepTotal(t0, 10)
        tracker.onStepTotal(t0 + 60_000, 100)

        assertNull(tracker.snapshot(t0 + 60_000).steps)
        assertNull(tracker.finish(t0 + 60_000).steps)
    }

    @Test fun `a counter that restarted below the baseline adds nothing`() {
        val tracker = run()
        tracker.onStepTotal(t0, 500)
        tracker.onStepTotal(t0 + 10_000, 20)

        assertEquals(0, tracker.snapshot(t0 + 10_000).steps)
    }

    @Test fun `climbing on foot counts only with enough steps around it`() {
        val tracker = run(WorkoutSport.HIKING)
        tracker.onStepTotal(t0, 0)
        tracker.onStepTotal(t0 + 30_000, 100)
        tracker.onAscentMinute(WearLinkProtocol.AscentMinute(t0, 60))
        // The second minute has no steps near it: an elevator.
        tracker.onAscentMinute(WearLinkProtocol.AscentMinute(t0 + 600_000, 300))

        assertEquals(60, tracker.ascentDecimetres())
        assertEquals(6, tracker.snapshot(t0 + 700_000).ascentMetres)
    }

    @Test fun `a minute of ascent that grows replaces its earlier value`() {
        val tracker = run(WorkoutSport.BIKING)
        tracker.onAscentMinute(WearLinkProtocol.AscentMinute(t0, 40))
        tracker.onAscentMinute(WearLinkProtocol.AscentMinute(t0, 90))

        assertEquals(90, tracker.ascentDecimetres())
        assertNull(tracker.snapshot(t0 + 60_000).floors)
    }

    @Test fun `stairs report floors, and sports without elevation report none`() {
        val stairs = run(WorkoutSport.STAIR_CLIMBING)
        stairs.onStepTotal(t0, 0)
        stairs.onStepTotal(t0 + 30_000, 100)
        stairs.onAscentMinute(WearLinkProtocol.AscentMinute(t0, 90))

        assertEquals(3, stairs.snapshot(t0 + 60_000).floors)
        val yoga = run(WorkoutSport.YOGA)
        yoga.onAscentMinute(WearLinkProtocol.AscentMinute(t0, 90))
        assertNull(yoga.snapshot(t0 + 60_000).ascentMetres)
        assertNull(yoga.finish(t0 + 60_000).ascentDecimetres)
    }

    @Test fun `heart rate average and maximum skip the paused time`() {
        val tracker = run()
        tracker.onHeartRate(t0 + 1_000, 100)
        tracker.onHeartRate(t0 + 2_000, 140)
        tracker.pause(t0 + 3_000)
        tracker.onHeartRate(t0 + 4_000, 190)

        val snapshot = tracker.snapshot(t0 + 4_000)
        assertEquals(120, snapshot.averageHeartRateBpm)
        assertEquals(140, snapshot.maxHeartRateBpm)
        assertEquals(190, snapshot.heartRateBpm)
    }

    @Test fun `a heart rate that stopped arriving is no longer shown`() {
        val tracker = run()
        tracker.onHeartRate(t0, 100)

        assertNull(tracker.snapshot(t0 + 60_000).heartRateBpm)
        assertEquals(100, tracker.snapshot(t0 + 60_000).averageHeartRateBpm)
    }

    @Test fun `finishing gives the wire workout, closing an open pause`() {
        val tracker = run()
        for (s in 0..60) tracker.onFix(fix(s, s * 3.0))
        tracker.onStepTotal(t0, 0)
        tracker.onStepTotal(t0 + 60_000, 170)
        tracker.pause(t0 + 70_000)

        val workout = tracker.finish(t0 + 100_000)

        assertEquals(t0, workout.startMillis)
        assertEquals(t0 + 100_000, workout.endMillis)
        assertEquals(56, workout.exerciseType)
        assertEquals(170, workout.steps)
        assertEquals(180.0, workout.distanceMetres!!.toDouble(), 2.0)
        assertEquals(1, workout.pauses.size)
        assertEquals(t0 + 100_000, workout.pauses[0].endMillis)
        assertTrue(workout.route.isNotEmpty())
        assertNull(workout.ascentDecimetres)
    }

    @Test fun `a restored point keeps the route whole`() {
        val tracker = run()
        val point = fix(0, 0.0).let { run().onFix(it)!! }
        tracker.restorePoint(point)

        assertEquals(listOf(point), tracker.finish(t0 + 60_000).route)
    }

    @Test fun `the checkpoint carries what a restart needs and reads back`() {
        val tracker = run()
        for (s in 0..30) tracker.onFix(fix(s, s * 3.0))
        tracker.onStepTotal(t0, 0)
        tracker.onStepTotal(t0 + 30_000, 80)
        tracker.pause(t0 + 10_000)
        tracker.resume(t0 + 20_000)
        tracker.pause(t0 + 40_000)

        val checkpoint = tracker.checkpoint(t0 + 45_000)
        val back = WorkoutCheckpoint.decode(checkpoint.encode())

        assertEquals(checkpoint, back)
        assertEquals("56", back!!.sportId)
        assertEquals(t0 + 40_000, back.pausedSince)
        assertEquals(1, back.pauses.size)
        assertNotNull(back.distanceMetres)
    }

    @Test fun `steps taken during a pause are not credited on resume`() {
        val tracker = run()
        tracker.onStepTotal(t0, 1_000)
        tracker.onStepTotal(t0 + 30_000, 1_050)
        tracker.pause(t0 + 31_000)
        tracker.onStepTotal(t0 + 40_000, 1_120)
        tracker.onStepTotal(t0 + 50_000, 1_200)
        tracker.resume(t0 + 60_000)
        tracker.onStepTotal(t0 + 70_000, 1_230)

        assertEquals(80, tracker.snapshot(t0 + 70_000).steps)
    }

    @Test fun `a reading from before the start is only a baseline, never a gain`() {
        val tracker = run()
        tracker.onStepTotal(t0 - 3_600_000, 5_000)
        tracker.onStepTotal(t0 + 5_000, 5_400)
        tracker.onStepTotal(t0 + 65_000, 5_460)

        assertEquals(60, tracker.snapshot(t0 + 65_000).steps)
    }

    @Test fun `a workout longer than the link allows is cut to the limit with its pauses and route`() {
        val tracker = run()
        val day = tech.mmarca.openwatch.wearlink.WearLinkWorkoutLines.MAX_DURATION_MILLIS
        for (s in 0..60) tracker.onFix(fix(s, s * 3.0))
        tracker.pause(t0 + day - 1_000)
        tracker.resume(t0 + day + 5_000)
        tracker.pause(t0 + day + 10_000)

        val workout = tracker.finish(t0 + day + 20_000)

        assertEquals(t0 + day, workout.endMillis)
        assertEquals(listOf(tech.mmarca.openwatch.wearlink.WearLinkWorkout.Pause(t0 + day - 1_000, t0 + day)), workout.pauses)
        assertNotNull(tech.mmarca.openwatch.wearlink.WearLinkWorkoutLines.parseHeader(
            tech.mmarca.openwatch.wearlink.WearLinkWorkoutLines.formatHeader(workout),
        ))
    }

    @Test fun `a route longer than the link carries keeps its end, thinned to every second point`() {
        val tracker = run()
        // One kept point every five seconds, 10 m apart: 9600 points, over 13 hours.
        val kept = (0 until 9_600).mapNotNull { tracker.onFix(fix(it * 5, it * 10.0)) }
        assertEquals("every point is stored", 9_600, kept.size)

        val route = tracker.finish(t0 + 9_600 * 5_000L).route

        // 4800 of every second point, and the last, which falls between them.
        assertEquals(4_801, route.size)
        assertEquals(kept.first(), route.first())
        assertEquals(kept.last(), route.last())
        assertEquals(10_000L, route[1].epochMillis - route[0].epochMillis)
    }

    @Test fun `thinning halves as often as it takes, keeps the first and the last point, and leaves a short route alone`() {
        val points = List(20_001) { tech.mmarca.openwatch.wearlink.WearLinkWorkout.RoutePoint(t0 + it * 5_000L, it, 0, null, null) }

        val thinned = WorkoutTracker.thinned(points)

        assertTrue(thinned.size <= tech.mmarca.openwatch.wearlink.WearLinkWorkoutLines.MAX_POINTS)
        assertEquals(5_001, thinned.size)
        assertEquals(points.first(), thinned.first())
        assertEquals(points.last(), thinned.last())
        assertEquals(points.take(9_000), WorkoutTracker.thinned(points.take(9_000)))
        assertEquals(points.take(2), WorkoutTracker.thinned(points.take(2), max = 1))
    }

    @Test fun `a day of points at the shortest gap fits what the store reads for one workout`() {
        val day = tech.mmarca.openwatch.wearlink.WearLinkWorkoutLines.MAX_DURATION_MILLIS
        val tracker = run()
        val kept = (0..(day / 5_000).toInt()).count { tracker.onFix(fix(it * 5, it * 10.0)) != null }

        assertEquals(WorkoutTracker.MAX_STORED_POINTS, kept)
    }
}

class WorkoutTrackerRestTest {

    private val t0 = 1_700_000_040_000L

    /** A second of the workout: the heart rate, and five readings of a swinging arm or of a still one. */
    private fun second(tracker: WorkoutTracker, s: Int, bpm: Int, still: Boolean) {
        tracker.onHeartRate(t0 + s * 1000L, bpm)
        for (i in 0 until 5) tracker.onAcceleration(t0 + s * 1000L + i * 200L, if (still) 9.81 else 9.81 + if (i % 2 == 0) 3.0 else -3.0)
    }

    @Test fun `a rest at the end gives the fall from the peak before the arm came to rest`() {
        val tracker = WorkoutTracker(WorkoutSport.RUNNING, t0, useGps = false)
        // A minute of running at 160, then 100 s standing: 160 falls to 130 after one minute.
        for (s in 0 until 60) second(tracker, s, 160, still = false)
        for (s in 60 until 160) second(tracker, s, (160 - (s - 60) * 30 / 60).coerceAtLeast(120), still = true)

        val rest = tracker.restRecovery(t0 + 160_000)!!

        assertEquals(t0 + 60_000, rest.startMillis)
        assertEquals(160, rest.peakBpm)
        assertEquals(30, rest.oneMinuteBpm)
        assertNull("the second minute is not over yet", rest.twoMinutesBpm)
        assertEquals(rest, tracker.snapshot(t0 + 160_000).rest)
        assertEquals(RecoveryAtStop.FROM_REST, RecoveryPlan.atStop(rest, 120, HeartRateZones.Basis(190, 60)))
    }

    @Test fun `without a rest the snapshot has none`() {
        val tracker = WorkoutTracker(WorkoutSport.RUNNING, t0, useGps = false)
        for (s in 0 until 90) second(tracker, s, 150, still = false)

        assertNull(tracker.snapshot(t0 + 90_000).rest)
    }
}

class WorkoutTrackerCreditTest {

    private val t0 = 1_700_000_040_000L

    @Test fun `a workout begun in the past is credited with the steps and heart rate since`() {
        val tracker = WorkoutTracker(WorkoutSport.WALKING, t0, useGps = false)
        val rows = (0 until 10).map { WearLinkProtocol.StepMinute(t0 + it * 60_000L, 100) }
        val beats = (0 until 10).map { WorkoutMetrics.Beat(t0 + it * 60_000L, 110 + it) }

        tracker.credit(rows, beats)
        tracker.onStepTotal(t0 + 600_000L, 5_000L)
        tracker.onStepTotal(t0 + 640_000L, 5_040L)

        val snapshot = tracker.snapshot(t0 + 640_000L)
        assertEquals(1_040, snapshot.steps)
        assertEquals(119, snapshot.maxHeartRateBpm)
        assertEquals(115, snapshot.averageHeartRateBpm)
        assertEquals(1_040, tracker.finish(t0 + 650_000L).steps)
    }

    @Test fun `a minute that ended before the start is not credited`() {
        val tracker = WorkoutTracker(WorkoutSport.WALKING, t0, useGps = false)

        tracker.credit(listOf(WearLinkProtocol.StepMinute(t0 - 60_000L, 100), WearLinkProtocol.StepMinute(t0, 80)), emptyList())
        tracker.onStepTotal(t0 + 60_000L, 1_000L)

        assertEquals(80, tracker.snapshot(t0 + 60_000L).steps)
    }
}
