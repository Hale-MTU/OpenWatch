package tech.mmarca.openwatch.wear

import android.app.NotificationManager
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The monitor that bounds a Do Not Disturb bedtime by the night window (`BedtimeWindowTest`, in `:wear:recording-core`). */
@RunWith(AndroidJUnit4::class)
class BedtimeMonitorTest {

    private val zone = ZoneId.of("Europe/Madrid")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var now = at(10, 15)
    private val changes = ArrayList<Boolean>()
    private val monitor = BedtimeMonitor(context, Handler(Looper.getMainLooper()), clock = { now }, zone = { zone }) { changes += it }

    init {
        // Setting the filter needs the policy grant, here as on a watch.
        shadowOf(notifications).setNotificationPolicyAccessGranted(true)
    }

    @After
    fun stop() {
        monitor.stop()
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
    }

    @Test
    fun `a Do Not Disturb left on by day is no bedtime until the window opens`() {
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        now = at(10, 20)
        monitor.start()
        assertFalse(monitor.isBedtime)

        now = at(10, 21)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(1))

        assertTrue(monitor.isBedtime)
        assertEquals(listOf(true), changes)

        now = at(11, 9)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofHours(12))

        assertFalse(monitor.isBedtime)
        assertEquals(listOf(true, false), changes)
    }

    @Test
    fun `inside the window Do Not Disturb counts as bedtime at once`() {
        notifications.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        now = at(10, 23)

        monitor.start()

        assertTrue(monitor.isBedtime)
    }
}
