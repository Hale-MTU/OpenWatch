package tech.mmarca.openwatch.wear

import android.app.Application
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The graph's settings flow keeps following the settings file after a garbage collection. */
@RunWith(AndroidJUnit4::class)
class WearGraphSettingsTest {

    @Test
    fun `the graph's settings follow a change made after a garbage collection`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        // Held as the application holds it, for the process.
        val graph = WearGraph(app)
        assertTrue(!graph.settings.value.spo2AtNight)

        // SharedPreferences holds its change listeners weakly: whatever the graph does not hold goes now.
        collectGarbage()
        WearSettings.setSpo2AtNight(app, true)
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(graph.settings.value.spo2AtNight)
    }

    /** Several full collections: one alone may leave an older object for the next. */
    private fun collectGarbage() = repeat(30) {
        System.gc()
        Thread.sleep(10)
    }
}
