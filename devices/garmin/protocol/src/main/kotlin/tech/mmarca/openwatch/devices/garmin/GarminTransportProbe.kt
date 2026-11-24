package tech.mmarca.openwatch.devices.garmin

/**
 * Asks a bonded Garmin device which GFDI transport it speaks. A port. Must
 * run after bonding: the characteristics sit behind an encrypted link.
 */
public interface GarminTransportProbe {

    /** Connects, enumerates, classifies, disconnects. Never throws: unreachable is UNREACHABLE. */
    public suspend fun probe(address: String): GarminGattReport
}
