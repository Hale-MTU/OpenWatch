package tech.mmarca.openwatch.wear

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * The notification that asks the wearer to sync before the watch drops rows
 * no phone has pulled ([SyncRetention]): from the fifth day without a pull,
 * and again once the pruning has reached them. Each is shown once per pull
 * and level, even across restarts ([PhonePulls.reminded]), and the next pull
 * takes it away.
 *
 * The service checks at its start and then about every [CHECK_MILLIS] on a
 * non-waking alarm, which fires when the watch is awake anyway: the reminder
 * never wakes it. One per process, held by [WearGraph].
 */
internal class SyncReminder(
    private val context: Context,
    private val pulls: PhonePulls,
    /** When the wearer allowed the first phone still trusted; null for none. */
    private val firstTrustedMillis: () -> Long?,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tick = AlarmManager.OnAlarmListener {
        check()
        arm()
    }

    /** Checks now and from then on; the service calls it once it runs. Main thread. */
    fun start() {
        check()
        arm()
    }

    fun stop() {
        alarms?.cancel(tick)
    }

    /** A phone pulled at [epochMillis]: recorded, and the reminder goes. Called on the link's thread. */
    fun onPulled(epochMillis: Long) {
        pulls.notePulled(epochMillis)
        notifications?.cancel(NOTIFICATION_ID)
    }

    /** Shows the reminder the pull's age calls for, unless it was shown already. Main thread. */
    fun check() {
        val warning = SyncRetention.warning(clock(), pulls.lastPulledMillis, firstTrustedMillis())
        val manager = notifications ?: return
        if (warning == null) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        val shown = SyncRetention.reminderToShow(warning, pulls.reminded) ?: return
        // Without the notification grant nothing is shown, and it is not counted as shown.
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.sync_reminder_channel_name), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.notify(NOTIFICATION_ID, build(shown.level))
        pulls.reminded = shown.key
    }

    private fun build(level: SyncRetention.Level): Notification {
        val open = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            // A notification's activity intent starts a task of its own; no flag (WearRecents).
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = when (level) {
            SyncRetention.Level.SOON -> R.string.sync_reminder_soon_text
            SyncRetention.Level.PRUNING -> R.string.status_sync_pruning
        }
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.sync_reminder_title))
            .setContentText(context.getString(text))
            .setStyle(Notification.BigTextStyle().bigText(context.getString(text)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
    }

    private fun arm() {
        alarms?.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + CHECK_MILLIS, TAG, tick, mainHandler)
    }

    companion object {
        private const val TAG = "OpenWatch:syncReminder"
        const val CHANNEL_ID = "sync_reminders"

        // 1 is the link's, 2 WearPhoneRequests', 3 the workout's, 5 a detected workout's.
        const val NOTIFICATION_ID = 4

        /** About hourly: the levels are days apart. */
        const val CHECK_MILLIS: Long = 60L * 60 * 1000
    }
}
