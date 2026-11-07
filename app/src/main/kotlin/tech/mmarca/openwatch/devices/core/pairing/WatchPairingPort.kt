package tech.mmarca.openwatch.devices.core.pairing

/** How the association dialog looks for the device. */
enum class CompanionFilter {
    /** A BLE scan for the address: a watch reached over BLE only. */
    BLE,

    /**
     * The BLE scan, or a Classic match on the address. A connected Classic
     * bond is found at once; a BLE scan never sees a Classic address.
     */
    BLE_OR_CLASSIC,
}

/**
 * The platform step that makes a scanned watch keep the app alive while it
 * is in range: an optional companion association. The bond is Android's,
 * made in its Bluetooth settings or by the vendor's onboarding. A port, so
 * onboarding is testable without a radio.
 */
interface WatchPairingPort {

    /**
     * Asks the OS to associate [address] as a companion. False is not a
     * failure: the watch is fully usable without it.
     */
    suspend fun associateCompanion(
        address: String,
        displayName: String?,
        filter: CompanionFilter = CompanionFilter.BLE,
    ): Boolean

    /** Drops the companion association for [address]. Best-effort, as above. */
    suspend fun disassociateCompanion(address: String)
}
