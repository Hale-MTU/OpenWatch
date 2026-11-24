package tech.mmarca.openwatch.devices.garmin

/** A transport must send each logical frame atomically, without interleaving chunks. */
public interface GarminFrameTransport {
    /** Sizes writes to a negotiated MTU. */
    public fun onMtuChanged(mtu: Int)

    public suspend fun sendFrame(frame: ByteArray)
}
