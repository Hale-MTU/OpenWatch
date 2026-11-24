package tech.mmarca.openwatch.link

import dagger.Lazy
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.Test
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkRecordingMode
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.host.OpenLinkHost

class LinkLiveSessionsTest {

    @Test
    fun `a recorded workout is refused as unsupported, to its owner, and nothing streams`() {
        val host = mockk<OpenLinkHost>(relaxed = true)
        val devices = mockk<LinkDevices>(relaxed = true)
        val sessions = LinkLiveSessions(devices, mockk(relaxed = true), mockk(relaxed = true), Lazy { host }, mockk(relaxed = true))
        val state = slot<OpenLinkRecordingState>()
        every { host.publishRecordingState(capture(state), any()) } returns Unit

        sessions.startRecording(
            OpenLinkRecordingRequest(sessionId = "ride-1", exerciseType = 8, startedAtMillis = 1_000L, deviceId = "pixel", mode = OpenLinkRecordingMode.RECORD),
            owner = "tech.mmarca.openvitals",
        )

        verify { host.publishRecordingState(any(), "tech.mmarca.openvitals") }
        assertEquals(OpenLinkRecordingPhase.FAILED, state.captured.phase)
        assertEquals(OpenLinkRecordingState.REASON_UNSUPPORTED, state.captured.reason)
        assertEquals(emptyMap<String, LinkSessionActivity>(), sessions.activity.value)
        verify(exactly = 0) { devices.devices }
    }

    @Test
    fun `a streamed start still reaches the devices, before and after a refusal`() {
        val host = mockk<OpenLinkHost>(relaxed = true)
        val devices = mockk<LinkDevices>(relaxed = true)
        val pixel = OpenLinkDevice("pixel", "Pixel", OpenLinkDeviceKind.WATCH, OpenLinkIntegration.WEAROS, supportsRecording = true)
        every { devices.devices } returns MutableStateFlow(listOf(pixel))
        // A scope that never runs: the session is registered and followed, no reading is awaited.
        val sessions = LinkLiveSessions(devices, mockk(relaxed = true), mockk(relaxed = true), Lazy { host }, CoroutineScope(StandardTestDispatcher()))
        val record = OpenLinkRecordingRequest("ride-1", 8, 1_000L, "pixel", OpenLinkRecordingMode.RECORD)
        val stream = OpenLinkRecordingRequest("stream-1", 8, 1_000L, "pixel", OpenLinkRecordingMode.STREAM)

        sessions.startRecording(stream.copy(sessionId = "before"), owner = "tech.mmarca.openvitals")
        sessions.startRecording(record, owner = "tech.mmarca.openvitals")
        sessions.startRecording(stream, owner = "tech.mmarca.openvitals")

        assertEquals(setOf("before", "stream-1"), sessions.activity.value.keys)
        assertEquals(listOf("pixel"), sessions.activity.value.getValue("stream-1").devices.map { it.id })
    }
}
