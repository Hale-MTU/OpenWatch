package tech.mmarca.openwatch.devices.blestandard

/**
 * One Blood Pressure Measurement (characteristic 0x2A35 of the Blood Pressure Service 0x1810),
 * as the Blood Pressure Profile lays it out, indicated once per reading:
 *
 * - Flags (uint8). Bit 0: the pressures are in kPa, else mmHg. Bit 1: a time stamp is present.
 *   Bit 2: the pulse rate is present. Bit 3: a user id is present. Bit 4: the measurement
 *   status is present. Bits 5 to 7 are reserved.
 * - Systolic, diastolic and mean arterial pressure, an SFLOAT each, always present.
 * - Time Stamp (Date Time, 7 bytes), when bit 1 is set.
 * - Pulse Rate (SFLOAT, beats per minute), when bit 2 is set.
 * - User ID (uint8; 0xFF is an unknown user), when bit 3 is set.
 * - Measurement Status (uint16), when bit 4 is set: bit 0 body movement, 1 cuff too loose, 2 an
 *   irregular pulse, 3 and 4 the pulse rate against its range, 5 an improper measurement
 *   position.
 */
public data class BloodPressureMeasurement(
    public val systolicMmHg: Double,
    public val diastolicMmHg: Double,
    public val meanArterialMmHg: Double,
    public val timeStamp: GattDateTime?,
    public val pulseRate: Double?,
    public val userId: Int?,
    public val status: Int?,
) {
    /** Whether the cuff read a pressure at all: it sends a reserved value for a reading it could not take. */
    public val isUsable: Boolean get() = systolicMmHg.isFinite() && diastolicMmHg.isFinite()

    public companion object {
        /** One kilopascal in millimetres of mercury. */
        public const val MMHG_PER_KPA: Double = 7.500_615_75

        /** [value] read whole, or null when it is shorter than its flags say. */
        public fun parse(value: ByteArray): BloodPressureMeasurement? {
            val fields = GattFields(value)
            val flags = fields.uint8() ?: return null
            val scale = if (flags.hasBit(0)) MMHG_PER_KPA else 1.0
            val systolic = fields.sfloat() ?: return null
            val diastolic = fields.sfloat() ?: return null
            val mean = fields.sfloat() ?: return null
            val time = if (flags.hasBit(1)) fields.dateTime() ?: return null else null
            val pulse = if (flags.hasBit(2)) fields.sfloat() ?: return null else null
            val user = if (flags.hasBit(3)) fields.uint8() ?: return null else null
            val status = if (flags.hasBit(4)) fields.uint16() ?: return null else null
            return BloodPressureMeasurement(
                systolicMmHg = systolic * scale,
                diastolicMmHg = diastolic * scale,
                meanArterialMmHg = mean * scale,
                timeStamp = time,
                pulseRate = pulse?.takeIf { it.isFinite() },
                userId = user,
                status = status,
            )
        }
    }
}
