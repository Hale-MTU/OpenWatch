package tech.mmarca.openwatch.wear

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.mmarca.openwatch.wearlink.WearLinkToken

/** The process's graph, as the manifest's application builds it. */
@RunWith(AndroidJUnit4::class)
@Config(application = OpenWatchWearApplication::class)
class WearGraphTest {

    private val app: OpenWatchWearApplication = ApplicationProvider.getApplicationContext()

    @Test
    fun `the process builds one graph, and each of its parts once`() {
        val graph = app.wearGraph

        assertSame(graph, app.wearGraph)
        assertSame(graph.store, app.wearGraph.store)
        assertSame(graph.trust, app.wearGraph.trust)
        assertSame(graph.heartRate, app.wearGraph.heartRate)
        assertSame(graph.liveHeartRate, app.wearGraph.liveHeartRate)
        assertSame(graph.entries, app.wearGraph.entries)
    }

    @Test
    fun `the sensors are read once, from what the recorders go by`() {
        val graph = app.wearGraph

        // The unit tests' watch has no sensor and no GPS receiver.
        assertEquals(WatchSensors.NONE, graph.sensors)
        assertSame(graph.sensors, app.wearGraph.sensors)
    }

    @Test
    fun `a plan reaches the heart rate before the step counter, which follows it`() {
        val recorders = app.wearGraph.recorders

        assertSame(app.wearGraph.heartRate, recorders[0])
        assertSame(app.wearGraph.steps, recorders[1])
        assertEquals(7, recorders.toSet().size)
    }

    @Test
    fun `an Allow from the notification lands in the store and the state the screen reads`() {
        val graph = app.wearGraph
        val address = "AA:BB:CC:DD:EE:07"
        val token = WearLinkToken.generate()
        // The unit tests carry no resources for the request's notification.
        graph.trust.onPending = null
        graph.trust.notePending(address, token, "Pixel")

        WearPhoneConfirmReceiver().onReceive(
            app,
            Intent(WearPhoneRequests.ACTION_ALLOW)
                .putExtra(WearPhoneRequests.EXTRA_ADDRESS, address)
                .putExtra(WearPhoneRequests.EXTRA_TOKEN, token)
                .putExtra(WearPhoneRequests.EXTRA_NAME, "Pixel"),
        )

        assertEquals(token, graph.trust.tokenFor(address))
        assertNull(graph.linkState.state.value.pending)
        assertEquals(listOf(address), graph.linkState.state.value.trusted.map { it.address })
    }
}
