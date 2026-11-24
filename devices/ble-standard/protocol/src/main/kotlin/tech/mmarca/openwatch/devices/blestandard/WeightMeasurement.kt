package tech.mmarca.openwatch.devices.blestandard

/**
 * One Weight Measurement (characteristic 0x2A9D of the Weight Scale Service 0x181D), as the
 * Weight Scale Profile lays it out, indicated once per weigh-in:
 *
 * - Flags (uint8). Bit 0: imperial units (pounds and inches), else SI (kilograms and metres).
 *   Bit 1: a time stamp is present. Bit 2: a user id is present. Bit 3: BMI and height are
 *   present. Bits 4 to 7 are reserved.
 * - Weight (uint16): 0.005 kg in SI, 0.01 lb in imperial. 0xFFFF is "measurement unsuccessful".
 * - Time Stamp (Date Time, 7 bytes), when bit 1 is set.
 * - User ID (uint8; 0xFF is an unknown user), when bit 2 is set.
 * - BMI (uint16, 0.1 kg/m²) and Height (uint16: 0.001 m in SI, 0.1 in imperial), when bit 3 is set.
 */
public data class WeightMeasurement(
    /** Null for a weigh-in the scale marked unsuccessful. */
    public val weightKg: Double?,
    public val timeStamp: GattDateTime?,
    public val userId: Int?,
    public val bmi: Double?,
    public val heightMetres: Double?,
) {
    public companion object {
        public const val KG_PER_LB: Double = 0.453_592_37
        private const val UNSUCCESSFUL = 0xFFFF

        /** [value] read whole, or null when it is shorter than its flags say. */
        public fun parse(value: ByteArray): WeightMeasurement? {
            val fields = GattFields(value)
            val flags = fields.uint8() ?: return null
            val imperial = flags.hasBit(0)
            val raw = fields.uint16() ?: return null
            val time = if (flags.hasBit(1)) fields.dateTime() ?: return null else null
            val user = if (flags.hasBit(2)) fields.uint8() ?: return null else null
            var bmi: Double? = null
            var height: Double? = null
            if (flags.hasBit(3)) {
                bmi = (fields.uint16() ?: return null) * 0.1
                val rawHeight = fields.uint16() ?: return null
                height = if (imperial) rawHeight * 0.1 * METRES_PER_INCH else rawHeight * 0.001
            }
            val weight = raw.takeIf { it != UNSUCCESSFUL }?.let { if (imperial) it * 0.01 * KG_PER_LB else it * 0.005 }
            return WeightMeasurement(weight, time, user, bmi, height)
        }

        private const val METRES_PER_INCH = 0.0254
    }
}
