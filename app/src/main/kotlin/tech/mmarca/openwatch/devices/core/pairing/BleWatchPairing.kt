package tech.mmarca.openwatch.devices.core.pairing

import javax.inject.Inject
import javax.inject.Singleton

/** [WatchPairingPort] over [CompanionDevicePairing]. The device address is never logged. */
@Singleton
class BleWatchPairing @Inject constructor(
    private val companionPairing: CompanionDevicePairing,
) : WatchPairingPort {

    override suspend fun associateCompanion(
        address: String,
        displayName: String?,
        filter: CompanionFilter,
    ): Boolean = companionPairing.associate(address, displayName, filter)

    override suspend fun disassociateCompanion(address: String) {
        companionPairing.disassociate(address)
    }
}
