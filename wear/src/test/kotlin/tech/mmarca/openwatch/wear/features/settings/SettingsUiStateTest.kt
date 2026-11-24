package tech.mmarca.openwatch.wear.features.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.wear.TrustedPhone
import tech.mmarca.openwatch.wear.WearLinkUiState

class SettingsUiStateTest {

    private val phone = TrustedPhone(address = "AA:BB", name = "Pixel", trustedAtMillis = 1L)
    private val everything = SensorAccess(heartRate = true, heartRateInBackground = true, steps = true, nearbyDevices = true)

    @Test
    fun allowedPairedPhoneOnAListeningLinkIsConnected() {
        assertTrue(SettingsUiState(link = WearLinkUiState(listening = true, trusted = listOf(phone))).phoneConnected)
    }

    @Test
    fun noAllowedPhoneIsNotConnected() {
        assertFalse(SettingsUiState(link = WearLinkUiState(listening = true)).phoneConnected)
    }

    @Test
    fun aPhoneThatLostItsPairingIsNotConnected() {
        val link = WearLinkUiState(listening = true, trusted = listOf(phone), bondLost = listOf(phone))

        assertFalse(SettingsUiState(link = link).phoneConnected)
    }

    @Test
    fun aLinkThatDoesNotListenIsNotConnected() {
        assertFalse(SettingsUiState(link = WearLinkUiState(listening = false, trusted = listOf(phone))).phoneConnected)
    }

    @Test
    fun sensorAccessIsGrantedOnlyWithEveryGrantTheWatchNeeds() {
        assertNull("not read yet", SettingsUiState().sensorAccessGranted)
        assertEquals(true, SettingsUiState(access = everything).sensorAccessGranted)
        assertEquals(false, SettingsUiState(access = everything.copy(heartRateInBackground = false)).sensorAccessGranted)
        assertEquals(false, SettingsUiState(access = everything.copy(steps = false)).sensorAccessGranted)
        assertEquals(false, SettingsUiState(access = everything.copy(nearbyDevices = false)).sensorAccessGranted)
        // A watch without a step counter asks for no step grant.
        assertEquals(true, SettingsUiState(access = everything.copy(steps = null)).sensorAccessGranted)
    }
}
