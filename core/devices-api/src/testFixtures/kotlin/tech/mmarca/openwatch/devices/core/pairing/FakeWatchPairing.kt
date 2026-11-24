package tech.mmarca.openwatch.devices.core.pairing

/** Records what onboarding asked the platform to do, and answers as set up. No radio, no Activity. */
class FakeWatchPairing : WatchPairingPort {
    var bondResult = WatchBondResult.BONDED
    var associateResult = true
    val calls = mutableListOf<String>()

    override suspend fun bond(address: String): WatchBondResult {
        calls.add("bond:$address")
        return bondResult
    }

    override suspend fun removeBond(address: String) {
        calls.add("removeBond:$address")
    }

    override suspend fun associateCompanion(
        address: String,
        displayName: String?,
        filter: CompanionFilter,
    ): Boolean {
        calls.add("associate:$address")
        return associateResult
    }

    override suspend fun disassociateCompanion(address: String) {
        calls.add("disassociate:$address")
    }

    override fun isCompanionAssociated(address: String): Boolean = false
}
