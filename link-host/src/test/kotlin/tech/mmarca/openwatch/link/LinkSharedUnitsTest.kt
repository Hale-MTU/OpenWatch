package tech.mmarca.openwatch.link

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkSettingKeys
import tech.mmarca.openlink.OpenLinkSettings
import tech.mmarca.openlink.OpenLinkTrust
import tech.mmarca.openlink.host.OpenLinkClientRecord
import tech.mmarca.openwatch.devices.FakeSharedPreferences
import tech.mmarca.openwatch.domain.preferences.SharedUnitChoice
import tech.mmarca.openwatch.domain.preferences.UnitSystemPreference

@OptIn(ExperimentalCoroutinesApi::class)
class LinkSharedUnitsTest {

    private val prefs = FakeSharedPreferences()
    private val settings = MutableStateFlow(OpenLinkSettings.EMPTY)
    private val openVitals = OpenLinkClientRecord(
        packageName = OPENVITALS,
        signerSha256 = "ab",
        trust = OpenLinkTrust.TRUSTED,
        capabilities = setOf(OpenLink.CAPABILITY_PROFILE, OpenLink.CAPABILITY_SETTINGS),
    )
    private val clients = MutableStateFlow(listOf(openVitals))
    private val apps = object : InstalledApps {
        val installed = mutableMapOf(OPENVITALS to "OpenVitals")
        override fun labelIfInstalled(packageName: String): String? = installed[packageName]
        override val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    }

    private fun TestScope.units() = LinkSharedUnits(prefs, settings, clients, apps, backgroundScope).also { runCurrent() }

    /** [from] sends [value]; the settings store takes it, as the link would. */
    private fun LinkSharedUnits.receive(from: String, value: Double) {
        val incoming = OpenLinkSettings().with(UNITS, listOf(value), 1_000)
        settings.value = settings.value.merge(incoming)
        onSettingsFrom(from, incoming)
    }

    @Test
    fun `the units the app that sent them chose decide, with its name`() = runTest {
        val units = units()
        assertNull(units.decided())

        units.receive(OPENVITALS, OpenLinkSettingKeys.UNITS_IMPERIAL)
        runCurrent()

        assertEquals(SharedUnitChoice(UnitSystemPreference.IMPERIAL, "OpenVitals"), units.decided())
    }

    @Test
    fun `who decides outlives a restart`() = runTest {
        units().receive(OPENVITALS, OpenLinkSettingKeys.UNITS_BY_REGION)

        val restarted = units()

        assertEquals(UnitSystemPreference.SYSTEM, restarted.decided()?.preference)
    }

    @Test
    fun `the app's choice stops deciding once it may not share settings, is not trusted or is gone`() = runTest {
        val units = units()
        units.receive(OPENVITALS, OpenLinkSettingKeys.UNITS_METRIC)
        runCurrent()

        clients.value = listOf(openVitals.copy(capabilities = setOf(OpenLink.CAPABILITY_PROFILE)))
        runCurrent()
        assertNull(units.decided())

        clients.value = listOf(openVitals.copy(trust = OpenLinkTrust.BLOCKED))
        runCurrent()
        assertNull(units.decided())

        clients.value = listOf(openVitals)
        runCurrent()
        assertEquals(UnitSystemPreference.METRIC, units.decided()?.preference)

        apps.installed.clear()
        apps.changes.tryEmit(Unit)
        runCurrent()
        assertNull(units.decided())
    }

    @Test
    fun `settings without units name no one`() = runTest {
        val units = units()

        units.onSettingsFrom(OPENVITALS, OpenLinkSettings().with(OpenLinkSettingKeys.GOAL_WATER_ML, listOf(2_500.0), 1_000))
        runCurrent()

        assertNull(units.decided())
    }

    private companion object {
        const val OPENVITALS = "tech.mmarca.openvitals"
        val UNITS = OpenLinkSettingKeys.UNITS_SYSTEM
    }
}
