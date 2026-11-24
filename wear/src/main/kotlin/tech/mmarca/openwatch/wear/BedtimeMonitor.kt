package tech.mmarca.openwatch.wear

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Handler
import android.provider.Settings
import android.util.Log
import java.time.ZoneId

/**
 * Tells whether the wearer has bedtime mode on, and calls [onChange] on
 * [handler]'s thread when that flips.
 *
 * Samsung's Wear OS keeps the mode in the global setting
 * `setting_bedtime_mode_running_state` (1 while it runs). Android does not
 * promise a third-party app may read a setting it does not document, so
 * when the read fails or the key is absent the monitor falls back to Do
 * Not Disturb, which bedtime mode switches on and every app may read. The
 * fallback also counts theater mode and a manual Do Not Disturb as
 * bedtime, so it counts only inside the night window ([BedtimeWindow]): a
 * Do Not Disturb left on by day is not a night. While it is on, a timer at
 * the window's next edge reads the state again.
 */
class BedtimeMonitor(
    private val context: Context,
    private val handler: Handler,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val onChange: (Boolean) -> Unit,
) {
    var isBedtime: Boolean = false
        private set

    private var source: String? = null

    /** Whether the fallback reads Do Not Disturb on, inside the window or not. */
    private var doNotDisturb = false

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = publish()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = publish()
    }

    private val edge = Runnable { publish() }

    fun start() {
        runCatching {
            context.contentResolver.registerContentObserver(Settings.Global.getUriFor(BEDTIME_SETTING), false, observer)
        }.onFailure { Log.w(TAG, "Cannot observe the bedtime setting", it) }
        context.registerReceiver(receiver, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), null, handler)
        isBedtime = read()
        scheduleEdge()
        Log.i(TAG, "Bedtime mode ${if (isBedtime) "on" else "off"} (from $source)")
    }

    fun stop() {
        handler.removeCallbacks(edge)
        runCatching { context.contentResolver.unregisterContentObserver(observer) }
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun publish() {
        val now = read()
        scheduleEdge()
        if (now == isBedtime) return
        isBedtime = now
        Log.i(TAG, "Bedtime mode ${if (now) "on" else "off"} (from $source)")
        onChange(now)
    }

    /** A Do Not Disturb that is on changes meaning at the window's edges; nothing else needs a timer. */
    private fun scheduleEdge() {
        handler.removeCallbacks(edge)
        if (source == DO_NOT_DISTURB && doNotDisturb) handler.postDelayed(edge, BedtimeWindow.millisToNextEdge(clock(), zone()))
    }

    private fun read(): Boolean {
        val setting = runCatching { Settings.Global.getInt(context.contentResolver, BEDTIME_SETTING, -1) }.getOrDefault(-1)
        if (setting >= 0) {
            source = BEDTIME_SETTING
            doNotDisturb = false
            return setting == 1
        }
        source = DO_NOT_DISTURB
        val filter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
        doNotDisturb = filter != null &&
            filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
            filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        return doNotDisturb && BedtimeWindow.contains(clock(), zone())
    }

    internal companion object {
        private const val TAG = "BedtimeMonitor"
        private const val BEDTIME_SETTING = "setting_bedtime_mode_running_state"
        private const val DO_NOT_DISTURB = "do not disturb"

        /** Whether the watch has Samsung's bedtime-mode setting, which the monitor follows before Do Not Disturb. */
        fun hasSamsungSetting(context: Context): Boolean =
            runCatching { Settings.Global.getString(context.contentResolver, BEDTIME_SETTING) != null }.getOrDefault(false)
    }
}
