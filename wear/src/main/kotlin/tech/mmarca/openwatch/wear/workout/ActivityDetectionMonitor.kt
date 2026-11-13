package tech.mmarca.openwatch.wear.workout

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log
import tech.mmarca.openwatch.wear.DebugProtocol
import tech.mmarca.openwatch.wear.MainActivity
import tech.mmarca.openwatch.wear.MetricStore
import tech.mmarca.openwatch.wear.R
import tech.mmarca.openwatch.wear.WearAppService
import tech.mmarca.openwatch.wear.WearMetrics
import tech.mmarca.openwatch.wear.WearSettings
import tech.mmarca.openwatch.wearlink.WorkoutSport

/**
 * Once a minute reads the last step minutes and, when [ActivityDetector] sees a walk or a run that no workout
 * covers, offers it in a notification. The minutes already walked are credited when the wearer accepts: the
 * workout then starts at the detected start (see [WorkoutRecorder.start]). Nothing is recorded by itself.
 *
 * An activity is offered once: [ignoreBefore] moves past it when it is offered, and past every minute during
 * which a workout was running, so a walk after a workout, or one the wearer left unanswered, does not come back
 * until a gap breaks it. The check is a read of a few rows and runs on the main thread's handler; a sleeping
 * processor only delays it, because the detection works backwards from the stored rows.
 */
internal class ActivityDetectionMonitor(
    private val context: Context,
    private val store: MetricStore,
    private val handler: Handler,
    private val workoutRunning: () -> Boolean,
) {
    private var ignoreBefore = System.currentTimeMillis()
    private var running = false

    private val tick = object : Runnable {
        override fun run() {
            check()
            if (running) handler.postDelayed(this, CHECK_MILLIS)
        }
    }

    fun start() {
        if (running) return
        running = true
        handler.postDelayed(tick, CHECK_MILLIS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
    }

    private fun check() {
        val now = System.currentTimeMillis()
        if (workoutRunning()) {
            ignoreBefore = now
            return
        }
        if (!WearSettings.autoDetect(context)) return
        val rows = store.since(WearMetrics.STEPS, now - LOOKBACK_MILLIS, LOOKBACK_ROWS)
        val found = ActivityDetector.detect(rows, now) ?: return
        if (found.startMillis <= ignoreBefore) return
        ignoreBefore = now
        DebugProtocol.event("Workout", "detected", "sport" to found.sport.id, "minutes" to found.minutes, "steps" to found.steps)
        offer(found)
    }

    private fun offer(found: ActivityDetector.Detection) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.detected_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val start = Intent(context, ActivityDetectionReceiver::class.java)
            .setAction(ACTION_START)
            .putExtra(EXTRA_SPORT, found.sport.id)
            .putExtra(EXTRA_START, found.startMillis)
        val startAction = Notification.Action.Builder(
            null, context.getString(R.string.detected_start),
            PendingIntent.getBroadcast(context, 1, start, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        ).build()
        val title = if (found.sport == WorkoutSport.RUNNING) R.string.detected_run else R.string.detected_walk
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(title))
            .setContentText(context.getString(R.string.detected_body, found.minutes, found.steps))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(startAction)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
            .onFailure { Log.w(TAG, "Cannot post the detected activity", it) }
    }

    companion object {
        const val ACTION_START = "tech.mmarca.openwatch.wear.START_DETECTED"
        const val EXTRA_SPORT = "sport"
        const val EXTRA_START = "start"
        const val CHANNEL_ID = "detected_activity"
        const val NOTIFICATION_ID = 3
        private const val TAG = "ActivityDetection"
        private const val CHECK_MILLIS = 60_000L
        private const val LOOKBACK_MILLIS = 3 * 60 * 60_000L
        private const val LOOKBACK_ROWS = 400
    }
}

/** Record from the notification: the workout starts at the detected start, without GPS (see the monitor). */
class ActivityDetectionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ActivityDetectionMonitor.ACTION_START) return
        val sport = WorkoutSport.fromId(intent.getStringExtra(ActivityDetectionMonitor.EXTRA_SPORT)) ?: return
        val start = intent.getLongExtra(ActivityDetectionMonitor.EXTRA_START, 0L).takeIf { it > 0L } ?: return
        context.getSystemService(NotificationManager::class.java).cancel(ActivityDetectionMonitor.NOTIFICATION_ID)
        WearAppService.startWorkout(context, sport, gps = false, startMillis = start)
    }
}
