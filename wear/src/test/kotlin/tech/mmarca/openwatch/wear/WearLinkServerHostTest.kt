package tech.mmarca.openwatch.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * The header's capability set, off the pure seam [WearLinkServerHost.capabilitiesFor]:
 * skin and SpO2 gate apart — a Pixel's raw skin sensor announces `sk` without
 * `o2` — and no source means no key. No watch needed.
 */
class WearLinkServerHostTest {

    private fun caps(live: Boolean = true, hrv: Boolean = true, skin: Boolean = true, spo2: Boolean = true) =
        WearLinkServerHost.capabilitiesFor(
            liveHeartRate = live,
            hrvAvailable = hrv,
            skinAvailable = skin,
            spo2Available = spo2,
        )

    @Test
    fun `a Pixel's raw skin sensor announces skin without SpO2`() {
        val capabilities = caps(skin = true, spo2 = false)
        assertTrue(WearLinkProtocol.CAP_SKIN_TEMPERATURE in capabilities)
        assertFalse(WearLinkProtocol.CAP_SPO2 in capabilities)
    }

    @Test
    fun `a Samsung tracker alone announces SpO2 without skin`() {
        val capabilities = caps(skin = false, spo2 = true)
        assertTrue(WearLinkProtocol.CAP_SPO2 in capabilities)
        assertFalse(WearLinkProtocol.CAP_SKIN_TEMPERATURE in capabilities)
    }

    @Test
    fun `no skin source announces neither key`() {
        val capabilities = caps(skin = false, spo2 = false)
        assertFalse(WearLinkProtocol.CAP_SKIN_TEMPERATURE in capabilities)
        assertFalse(WearLinkProtocol.CAP_SPO2 in capabilities)
    }

    @Test
    fun `both sources announce both keys`() {
        val capabilities = caps(skin = true, spo2 = true)
        assertTrue(WearLinkProtocol.CAP_SKIN_TEMPERATURE in capabilities)
        assertTrue(WearLinkProtocol.CAP_SPO2 in capabilities)
    }

    @Test
    fun `no HRV source takes the respiratory rate with it`() {
        val capabilities = caps(hrv = false)
        assertFalse(WearLinkProtocol.CAP_HRV in capabilities)
        assertFalse(WearLinkProtocol.CAP_RESPIRATORY_RATE in capabilities)
        assertTrue(WearLinkProtocol.CAP_HRV in caps(hrv = true))
    }

    @Test
    fun `a workout's pause and route lines are never capabilities themselves`() {
        val capabilities = caps()
        assertFalse("wp" in capabilities)
        assertFalse("wr" in capabilities)
        assertTrue(WearLinkProtocol.CAP_WORKOUTS in capabilities)
    }
}
