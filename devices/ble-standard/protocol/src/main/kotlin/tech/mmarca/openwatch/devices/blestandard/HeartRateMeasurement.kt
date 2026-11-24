package tech.mmarca.openwatch.devices.blestandard

/** Whether the strap can tell it sits on the skin, and whether it does (the measurement's flags, bits 1 and 2). */
public enum class SensorContact {
    /** The strap cannot tell. Its readings count. */
    NOT_SUPPORTED,

    /** The strap can tell, and it is off the skin: the value means nothing. */
    NOT_DETECTED,

    DETECTED,
}

/**
 * One Heart Rate Measurement (characteristic 0x2A37 of the Heart Rate Service 0x180D), as the
 * Heart Rate Profile lays it out:
 *
 * - Flags (uint8). Bit 0: the heart rate is a uint16, else a uint8. Bits 1 and 2: the sensor
 *   contact status (bit 2 "supported", bit 1 "detected"). Bit 3: the energy expended is present.
 *   Bit 4: one or more RR intervals are present. Bits 5 to 7 are reserved.
 * - Heart Rate Measurement Value: beats per minute, uint8 or uint16.
 * - Energy Expended (uint16, kilojoules), when bit 3 is set. 0xFFFF means "at least 65535".
 * - RR-Interval (uint16 each, in 1/1024 s), when bit 4 is set: as many as fill the rest, oldest
 *   first.
 */
public data class HeartRateMeasurement(
    public val beatsPerMinute: Int,
    public val sensorContact: SensorContact,
    /** Kilojoules since the strap last reset the count; null when absent. */
    public val energyExpendedKilojoules: Int?,
    /** The beat-to-beat intervals the value carried, in 1/1024 s as sent, oldest first. */
    public val rrIntervals1024: List<Int>,
) {
    /** The RR intervals in milliseconds. */
    public val rrIntervalsMillis: List<Double> get() = rrIntervals1024.map { it * 1000.0 / RR_UNITS_PER_SECOND }

    /** Whether the value says something about a heart: on the skin, or unable to tell, and above zero. */
    public val isUsable: Boolean get() = sensorContact != SensorContact.NOT_DETECTED && beatsPerMinute > 0

    public companion object {
        /** The RR interval's resolution: 1/1024 of a second. */
        public const val RR_UNITS_PER_SECOND: Int = 1024

        /** [value] read whole, or null when it is shorter than its flags say. */
        public fun parse(value: ByteArray): HeartRateMeasurement? {
            val fields = GattFields(value)
            val flags = fields.uint8() ?: return null
            val bpm = (if (flags.hasBit(0)) fields.uint16() else fields.uint8()) ?: return null
            val contact = when {
                !flags.hasBit(2) -> SensorContact.NOT_SUPPORTED
                flags.hasBit(1) -> SensorContact.DETECTED
                else -> SensorContact.NOT_DETECTED
            }
            val energy = if (flags.hasBit(3)) fields.uint16() ?: return null else null
            val intervals = if (flags.hasBit(4)) {
                // The rest is RR intervals, two bytes each; a stray last byte is no interval.
                generateSequence { fields.uint16() }.toList()
            } else {
                emptyList()
            }
            return HeartRateMeasurement(bpm, contact, energy, intervals)
        }
    }
}
