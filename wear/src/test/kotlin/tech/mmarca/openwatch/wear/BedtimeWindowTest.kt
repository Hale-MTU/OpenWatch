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

/** The night window itself, and the monitor that bounds a Do Not Disturb bedtime by it. */
@RunWith(AndroidJUnit4::class)
class BedtimeWindowTest {

    private val zone = ZoneId.of("Europe/Madrid")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(2026, 11, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `the window runs from nine in the evening to nine in the morning`() {
        assertFalse(BedtimeWindow.contains(at(10, 20, 59), zone))
        assertTrue(BedtimeWindow.contains(at(10, 21), zone))
        assertTrue(BedtimeWindow.contains(at(11, 3), zone))
        assertTrue(BedtimeWindow.contains(at(11, 8, 59), zone))
        assertFalse(BedtimeWindow.contains(at(11, 9), zone))
    }

    @Test
    fun `the next edge is the window's next opening or closing`() {
        assertEquals(Duration.ofHours(1).toMillis(), BedtimeWindow.millisToNextEdge(at(10, 20), zone))
        assertEquals(Duration.ofHours(10).toMillis(), BedtimeWindow.millisToNextEdge(at(10, 23), zone))
        assertEquals(Duration.ofHours(12).toMillis(), BedtimeWindow.millisToNextEdge(at(10, 9), zone))
    }

    @Test
    fun `the night the clocks go back is an hour longer`() {
        // 25 October 2026: Madrid falls back from 03:00 to 02:00.
        val evening = LocalDateTime.of(2026, 10, 24, 21, 0).atZone(zone).toInstant().toEpochMilli()

        assertEquals(Duration.ofHours(13).toMillis(), BedtimeWindow.millisToNextEdge(evening, zone))
    }

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
