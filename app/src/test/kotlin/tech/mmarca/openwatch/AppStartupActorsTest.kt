package tech.mmarca.openwatch

import org.junit.Assert.assertEquals
import org.junit.Test

class AppStartupActorsTest {

    private val started = mutableListOf<String>()

    private fun actor(name: String, failure: Exception? = null) = StartupActor(name) {
        started += name
        failure?.let { throw it }
    }

    @Test
    fun `every contributed actor starts once`() {
        AppStartupActors(setOf(actor("scale listener"), actor("garmin notifications"), actor("sync schedules"))).start()

        assertEquals(listOf("garmin notifications", "scale listener", "sync schedules"), started)
    }

    @Test
    fun `one actor that throws neither stops the others nor reaches the caller`() {
        AppStartupActors(
            setOf(
                actor("garmin notifications", SecurityException("no Bluetooth grant")),
                actor("phone activity"),
                actor("sync schedules", IllegalStateException("WorkManager not ready")),
                actor("scale listener"),
            ),
        ).start()

        assertEquals(listOf("garmin notifications", "phone activity", "scale listener", "sync schedules"), started)
    }
}
