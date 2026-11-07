package tech.mmarca.openwatch

import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import tech.mmarca.openwatch.features.watches.WatchAutoSyncScheduler

class AppStartupActorsTest {

    private val autoSyncScheduler = mockk<WatchAutoSyncScheduler>(relaxed = true)

    @Test
    fun `the sync schedules are restored at start`() {
        AppStartupActors(autoSyncScheduler).start()

        verify(exactly = 1) { autoSyncScheduler.restoreAll() }
    }
}
