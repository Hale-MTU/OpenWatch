package tech.mmarca.openwatch.features.connectedapps

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openlink.host.OpenLinkClientRecord
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openlink.host.OpenLinkHostCore
import tech.mmarca.openwatch.link.FirstPartyApps
import tech.mmarca.openwatch.link.LinkLiveSessions
import tech.mmarca.openwatch.util.MainDispatcherRule

/** Each app that asked, under its own name, with what the card must warn about. */
class ConnectedAppsViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val records = MutableStateFlow<List<OpenLinkClientRecord>>(emptyList())
    private val core = mockk<OpenLinkHostCore>(relaxed = true) { every { clients } returns records }
    private val host = mockk<OpenLinkHost> {
        every { connected } returns MutableStateFlow(setOf("tech.mmarca.openvitals"))
        every { activeSessions } returns MutableStateFlow(emptyList())
    }
    private val live = mockk<LinkLiveSessions> { every { activity } returns MutableStateFlow(emptyMap()) }
    private val firstParty = FirstPartyApps(
        packageNames = setOf("tech.mmarca.openvitals"),
        knownSigners = setOf("bb"),
        names = setOf("OpenVitals"),
    )
    private val labels = mapOf("tech.mmarca.openvitals" to "OpenVitals", "org.example.fake" to "OpenVitals Pro")

    private fun viewModel() = ConnectedAppsViewModel({ labels[it] ?: it }, firstParty, core, host, live)

    @Test
    fun `apps go by their name, the bound one is in use, and a borrowed name is flagged`() = runTest {
        records.value = listOf(
            OpenLinkClientRecord("tech.mmarca.openvitals", "bb", OpenLinkTrust.TRUSTED, knownSigner = true),
            OpenLinkClientRecord("org.example.fake", "cc", OpenLinkTrust.PENDING),
        )
        val vm = viewModel()
        backgroundScope.launch { vm.apps.collect {} }
        runCurrent()

        val (vitals, fake) = vm.apps.value
        assertEquals("OpenVitals", vitals.label)
        assertTrue(vitals.inUse)
        assertNull(vitals.lookalikeOf)
        assertEquals("OpenVitals Pro", fake.label)
        assertEquals("OpenVitals", fake.lookalikeOf)
    }

    @Test
    fun `the switches reach the trust store`() = runTest {
        val vm = viewModel()

        vm.setCapability("org.example.fake", "live", granted = true)
        vm.setCapability("org.example.fake", "live", granted = false)
        vm.block("org.example.fake")

        io.mockk.verifyOrder {
            core.grant("org.example.fake", "live")
            core.revoke("org.example.fake", "live")
            core.block("org.example.fake")
        }
    }
}
