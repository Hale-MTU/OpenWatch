package tech.mmarca.openwatch.link

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstPartyAppsTest {

    private val apps = FirstPartyApps(
        packageNames = setOf("tech.mmarca.openvitals", "tech.mmarca.openvitals.debug"),
        knownSigners = setOf("AA:BB:CC"),
        names = setOf("OpenVitals"),
    )

    @Test
    fun `every first-party name is known under the first-party keys, and no other name`() {
        assertEquals(
            mapOf("tech.mmarca.openvitals" to setOf("AA:BB:CC"), "tech.mmarca.openvitals.debug" to setOf("AA:BB:CC")),
            apps.knownClients,
        )
    }

    @Test
    fun `a first-party name under a known key is not flagged`() {
        assertFalse(apps.usesNameWithoutKey("tech.mmarca.openvitals", knownSigner = true))
    }

    @Test
    fun `a first-party name under another key is flagged`() {
        assertTrue(apps.usesNameWithoutKey("tech.mmarca.openvitals", knownSigner = false))
        assertTrue(apps.usesNameWithoutKey("tech.mmarca.openvitals.debug", knownSigner = false))
    }

    @Test
    fun `any other app is not flagged, whatever its key`() {
        assertFalse(apps.usesNameWithoutKey("com.example.other", knownSigner = false))
    }

    @Test
    fun `a label that carries a first-party name under another package is a look-alike, however it is spelt`() {
        assertEquals("OpenVitals", apps.lookalikeOf("OpenVitals", "com.example.health"))
        assertEquals("OpenVitals", apps.lookalikeOf("Open Vitals Pro", "com.example.health"))
        assertEquals("OpenVitals", apps.lookalikeOf("open-vitals", "com.example.health"))
        // The real one, and an app with a name of its own, are not.
        assertEquals(null, apps.lookalikeOf("OpenVitals", "tech.mmarca.openvitals"))
        assertEquals(null, apps.lookalikeOf("Strava", "com.strava"))
    }

    @Test
    fun `a signer is shown as its first 64 bits in groups of four`() {
        assertEquals(
            "0416 C065 1543 E951",
            signerFingerprint("0416c0651543e951ef3b6c1ed9beb13833bff3a2be9ddacf492270712861e05d"),
        )
    }
}
