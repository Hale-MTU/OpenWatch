package tech.mmarca.openwatch.devices.core

import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice

/**
 * How the app's own add-a-device flow finds and pairs a family's devices, for a family that has
 * no flow of its own: the scan listens for [scanServiceUuids], every device a family [claim]s is
 * offered under that family, and the one picked is handed to [pair]. The watches and the S400
 * scale keep their own flows and declare none.
 */
interface DevicePairing {

    /** The 128-bit service UUIDs, lowercase, whose advertisements the scan listens for. */
    val scanServiceUuids: Set<String>

    /** The kind [device] would be paired as, or null when it is not one of the family's. */
    fun claim(device: BleDiscoveredDevice): BleDeviceKind?

    /**
     * Pairs [device] under [displayName]: whatever bond or association the family needs, then
     * its registry row. Nothing is registered when it fails.
     */
    suspend fun pair(device: BleDiscoveredDevice, displayName: String): DevicePairingResult
}

/** How a [DevicePairing.pair] ended. */
sealed interface DevicePairingResult {

    /** Registered, as [deviceId]. */
    data class Paired(val deviceId: String) : DevicePairingResult

    data class Failed(val reason: PairingFailure) : DevicePairingResult
}

/** Why a pairing did not finish. The screen words each. */
enum class PairingFailure {
    /** The pairing dialog was dismissed or timed out. */
    BOND_REFUSED,

    /** The device could not be reached: asleep, out of range, or Bluetooth is off. */
    UNREACHABLE,

    /** Android's companion dialog was declined, and the family hears the device only through it. */
    NOT_ASSOCIATED,
}
