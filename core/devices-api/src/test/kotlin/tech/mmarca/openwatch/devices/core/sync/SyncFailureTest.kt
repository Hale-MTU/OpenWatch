package tech.mmarca.openwatch.devices.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncFailureTest {

    @Test
    fun `the schedule retries what can clear up by itself, and waits for the user on the rest`() {
        val retried = listOf(
            SyncFailure.RadioBusy,
            SyncFailure.Unreachable,
            SyncFailure.Interrupted,
            SyncFailure.RateLimited,
            SyncFailure.NotSaved,
            SyncFailure.Unknown(IllegalStateException("link dropped")),
            SyncFailure.Described(message = 1, transient = true),
        )
        val waiting = listOf(
            SyncFailure.HealthConnectPermission,
            SyncFailure.BluetoothPermission,
            SyncFailure.BluetoothOff,
            SyncFailure.SyncPaused,
            SyncFailure.Described(message = 1),
        )

        assertEquals(retried, retried.filter { it.transient })
        assertEquals(emptyList<SyncFailure>(), waiting.filter { it.transient })
    }
}
