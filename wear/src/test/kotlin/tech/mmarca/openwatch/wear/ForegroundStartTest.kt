package tech.mmarca.openwatch.wear

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundStartTest {

    private val health = ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
    private val link = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE

    @Test
    fun `a refused health start falls back to the link alone`() {
        assertEquals(listOf(link or health, link), ForegroundStart.attempts(link or health))
    }

    @Test
    fun `the link alone is tried once`() {
        assertEquals(listOf(link), ForegroundStart.attempts(link))
    }

    @Test
    fun `a service running without health takes it once a sensor is granted`() {
        assertTrue(ForegroundStart.healthMissing(running = link, sensing = true, healthTypeAvailable = true))
        assertFalse(ForegroundStart.healthMissing(running = link or health, sensing = true, healthTypeAvailable = true))
        assertFalse(ForegroundStart.healthMissing(running = link, sensing = false, healthTypeAvailable = true))
        assertFalse(ForegroundStart.healthMissing(running = link, sensing = true, healthTypeAvailable = false))
    }
}
