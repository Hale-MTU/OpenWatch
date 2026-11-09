package tech.mmarca.openwatch.features.connectedapps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstPartyAppsTest {

    private val apps = FirstPartyApps(
        packageNames = setOf("tech.mmarca.openvitals", "tech.mmarca.openvitals.debug"),
        knownSigners = setOf("AA:BB:CC"),
    )

    @Test
    fun `a first-party name under a known key is not flagged`() {
        assertFalse(apps.usesNameWithoutKey("tech.mmarca.openvitals", "aabbcc"))
    }

    @Test
    fun `a first-party name under another key is flagged`() {
        assertTrue(apps.usesNameWithoutKey("tech.mmarca.openvitals", "ddeeff"))
        assertTrue(apps.usesNameWithoutKey("tech.mmarca.openvitals.debug", "ddeeff"))
    }

    @Test
    fun `an app with several signers never matches one known key`() {
        assertTrue(apps.usesNameWithoutKey("tech.mmarca.openvitals", "aabbcc+ddeeff"))
    }

    @Test
    fun `any other app is not flagged, whatever its key`() {
        assertFalse(apps.usesNameWithoutKey("com.example.other", "ddeeff"))
    }
}
