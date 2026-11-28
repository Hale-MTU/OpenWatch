package tech.mmarca.openwatch.link

import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkRecordingMode
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.host.OpenLinkHost

class LinkLiveSessionsTest {

    @Test
    fun `a streamed start reaches the devices`() {
        val host = mockk<OpenLinkHost>(relaxed = true)
        val devices = mockk<LinkDevices>(relaxed = true)
        val pixel = OpenLinkDevice("pixel", "Pixel", OpenLinkDeviceKind.WATCH, OpenLinkIntegration.WEAROS, supportsRecording = true)
        every { devices.devices } returns MutableStateFlow(listOf(pixel))
        // A scope that never runs: the session is registered and followed, no reading is awaited.
        val sessions = LinkLiveSessions(devices, mockk(relaxed = true), mockk(relaxed = true), Lazy { host }, CoroutineScope(StandardTestDispatcher()))
        val stream = OpenLinkRecordingRequest("stream-1", 8, 1_000L, "pixel", OpenLinkRecordingMode.STREAM)

        sessions.startRecording(stream)

        assertEquals(setOf("stream-1"), sessions.activity.value.keys)
        assertEquals(listOf("pixel"), sessions.activity.value.getValue("stream-1").devices.map { it.id })
    }
}
