package tech.mmarca.openwatch.devices.core

import android.os.SystemClock
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The single-process radio discipline: one exclusive, expiring, per-address lease. The clock is mocked. */
class RadioLeasesTest {

    private var nowMillis = 0L

    private companion object {
        const val ADDRESS = "AA:BB:CC:DD:EE:01"
        const val OTHER_ADDRESS = "AA:BB:CC:DD:EE:02"
    }

    private val leases = RadioLeaseRegistry()

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { nowMillis }
    }

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    @Test
    fun `a held lease excludes another owner on the same address`() {
        assertTrue(leases.acquire(ADDRESS, "sync", 10_000))
        assertFalse(leases.acquire(ADDRESS, "forwarder", 10_000))
        assertEquals("sync", leases.owner(ADDRESS))
    }

    @Test
    fun `leases are keyed by address, not global`() {
        assertTrue(leases.acquire(ADDRESS, "sync", 10_000))
        assertTrue(leases.acquire(OTHER_ADDRESS, "forwarder", 10_000))
    }

    @Test
    fun `re-acquiring extends a lease the owner already holds`() {
        assertTrue(leases.acquire(ADDRESS, "sync", 1_000))
        nowMillis += 900
        assertTrue(leases.acquire(ADDRESS, "sync", 1_000))
        // Without the extension the original TTL would have lapsed here.
        nowMillis += 900
        assertEquals("sync", leases.owner(ADDRESS))
    }

    @Test
    fun `an expired lease cannot block a new holder`() {
        assertTrue(leases.acquire(ADDRESS, "sync", 1_000))
        nowMillis += 1_000
        assertNull(leases.owner(ADDRESS))
        assertTrue(leases.acquire(ADDRESS, "forwarder", 1_000))
    }

    @Test
    fun `renew extends only while held and unwanted`() {
        assertTrue(leases.acquire(ADDRESS, "forwarder", 1_000))
        assertTrue(leases.renew(ADDRESS, "forwarder", 1_000))

        // A waiter makes the holder's next renew fail: that is how an indefinite lease is given up.
        leases.request(ADDRESS, "sync")
        assertFalse(leases.renew(ADDRESS, "forwarder", 1_000))
    }

    @Test
    fun `renew fails once the lease expired or was taken`() {
        assertTrue(leases.acquire(ADDRESS, "sync", 1_000))
        nowMillis += 1_000
        assertFalse(leases.renew(ADDRESS, "sync", 1_000))

        assertTrue(leases.acquire(ADDRESS, "forwarder", 1_000))
        assertFalse(leases.renew(ADDRESS, "sync", 1_000))
    }

    @Test
    fun `acquiring satisfies the owner's own outstanding request`() {
        assertTrue(leases.acquire(ADDRESS, "forwarder", 1_000))
        leases.request(ADDRESS, "sync")
        nowMillis += 1_000

        assertTrue(leases.acquire(ADDRESS, "sync", 1_000))
        // The waiter entry is gone: sync can renew its own fresh lease.
        assertTrue(leases.renew(ADDRESS, "sync", 1_000))
    }

    @Test
    fun `request against a free or own lease is a no-op`() {
        leases.request(ADDRESS, "sync")
        assertTrue(leases.acquire(ADDRESS, "sync", 1_000))
        leases.request(ADDRESS, "sync")
        assertTrue(leases.renew(ADDRESS, "sync", 1_000))
    }

    @Test
    fun `a waiter that gave up expires, so the holder can renew again`() {
        assertTrue(leases.acquire(ADDRESS, "notifications", 15_000))
        leases.request(ADDRESS, "sync")
        assertFalse(leases.renew(ADDRESS, "notifications", 15_000))

        // The sync timed out after 8 s and never took the lease. Its request used to stay for
        // ever, and every later renew on this address failed.
        nowMillis += 10_000
        assertTrue(leases.renew(ADDRESS, "notifications", 15_000))
    }

    @Test
    fun `withdraw takes a request back at once`() {
        assertTrue(leases.acquire(ADDRESS, "notifications", 15_000))
        leases.request(ADDRESS, "sync")

        leases.withdraw(ADDRESS, "sync")

        assertTrue(leases.renew(ADDRESS, "notifications", 15_000))
    }

    @Test
    fun `withdraw by someone else leaves the request in place`() {
        assertTrue(leases.acquire(ADDRESS, "notifications", 15_000))
        leases.request(ADDRESS, "sync")

        leases.withdraw(ADDRESS, "find")

        assertFalse(leases.renew(ADDRESS, "notifications", 15_000))
    }

    @Test
    fun `work with an end keeps the radio while someone waits`() {
        assertTrue(leases.acquire(ADDRESS, "sync", 15_000))
        leases.request(ADDRESS, "notifications")

        // Yielding here let the lease lapse mid-sync, and the waiter opened a second link.
        nowMillis += 5_000
        assertTrue(leases.renew(ADDRESS, "sync", 15_000, yieldToWaiter = false))
        nowMillis += 14_000
        assertFalse(leases.acquire(ADDRESS, "notifications", 15_000))
        assertEquals("sync", leases.owner(ADDRESS))
    }

    @Test
    fun `release keeps the address unavailable for the settle window`() {
        // Android closes a BluetoothGatt asynchronously; a connect inside that window is the collision the lease prevents.
        assertTrue(leases.acquire(ADDRESS, "sync", 10_000))
        leases.release(ADDRESS, "sync")

        assertFalse(leases.acquire(ADDRESS, "forwarder", 1_000))
        nowMillis += 300
        assertTrue(leases.acquire(ADDRESS, "forwarder", 1_000))
    }

    @Test
    fun `a late release by a non-holder does not cancel the new holder`() {
        assertTrue(leases.acquire(ADDRESS, "sync", 1_000))
        nowMillis += 1_000
        assertTrue(leases.acquire(ADDRESS, "forwarder", 10_000))

        leases.release(ADDRESS, "sync")

        assertEquals("forwarder", leases.owner(ADDRESS))
        // And the new holder's lease was not shortened to the settle window.
        nowMillis += 5_000
        assertEquals("forwarder", leases.owner(ADDRESS))
    }
}
