package tech.mmarca.openwatch.devices.blestandard

/** Where the meter says a sample came from: the low nibble of the Type-Sample Location field. */
public enum class GlucoseSampleType(public val code: Int) {
    CAPILLARY_WHOLE_BLOOD(1),
    CAPILLARY_PLASMA(2),
    VENOUS_WHOLE_BLOOD(3),
    VENOUS_PLASMA(4),
    ARTERIAL_WHOLE_BLOOD(5),
    ARTERIAL_PLASMA(6),
    UNDETERMINED_WHOLE_BLOOD(7),
    UNDETERMINED_PLASMA(8),
    INTERSTITIAL_FLUID(9),

    /** A check of the meter with its control solution: no one's blood. */
    CONTROL_SOLUTION(10),
    ;

    public companion object {
        public fun of(code: Int): GlucoseSampleType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * One Glucose Measurement (characteristic 0x2A18 of the Glucose Service 0x1808), as the Glucose
 * Profile lays it out, notified once per stored record the meter reports:
 *
 * - Flags (uint8). Bit 0: the time offset is present. Bit 1: the concentration and the
 *   type-sample location are present. Bit 2: the concentration is in mol/L, else kg/L. Bit 3: the
 *   sensor status annunciation is present. Bit 4: a Glucose Measurement Context follows, with the
 *   same sequence number. Bits 5 to 7 are reserved.
 * - Sequence Number (uint16): the record's number in the meter, rising.
 * - Base Time (Date Time, 7 bytes), the meter's clock.
 * - Time Offset (sint16, minutes after the base time), when bit 0 is set.
 * - Glucose Concentration (SFLOAT) and Type-Sample Location (uint8: the type in the low nibble,
 *   the location in the high one), when bit 1 is set.
 * - Sensor Status Annunciation (uint16), when bit 3 is set.
 */
public data class GlucoseMeasurement(
    public val sequenceNumber: Int,
    public val baseTime: GattDateTime,
    public val timeOffsetMinutes: Int,
    /** The concentration in mmol/L; null when the record carries none, NaN for a reserved value. */
    public val mmolPerLiter: Double?,
    public val sampleType: GlucoseSampleType?,
    public val sampleLocation: Int?,
    public val sensorStatus: Int?,
    public val contextFollows: Boolean,
) {
    public companion object {
        /** Glucose's molar mass, in g/mol: what turns a mass concentration into a molar one. */
        public const val GLUCOSE_GRAMS_PER_MOLE: Double = 180.156

        /** [value] read whole, or null when it is shorter than its flags say. */
        public fun parse(value: ByteArray): GlucoseMeasurement? {
            val fields = GattFields(value)
            val flags = fields.uint8() ?: return null
            val sequence = fields.uint16() ?: return null
            val base = fields.dateTime() ?: return null
            val offset = if (flags.hasBit(0)) fields.sint16() ?: return null else 0
            var concentration: Double? = null
            var type: GlucoseSampleType? = null
            var location: Int? = null
            if (flags.hasBit(1)) {
                val raw = fields.sfloat() ?: return null
                val typeLocation = fields.uint8() ?: return null
                // kg/L, converted through the molar mass: 1 kg/L is 1000 g/L, 1000/180.156 mol/L.
                concentration = if (flags.hasBit(2)) raw * 1000.0 else raw * 1_000_000.0 / GLUCOSE_GRAMS_PER_MOLE
                type = GlucoseSampleType.of(typeLocation and 0x0F)
                location = typeLocation shr 4
            }
            val status = if (flags.hasBit(3)) fields.uint16() ?: return null else null
            return GlucoseMeasurement(sequence, base, offset, concentration, type, location, status, flags.hasBit(4))
        }
    }
}

/** When the meter says a reading was taken against a meal: the Glucose Measurement Context's Meal field. */
public enum class GlucoseMeal(public val code: Int) {
    PREPRANDIAL(1),
    POSTPRANDIAL(2),
    FASTING(3),
    CASUAL(4),
    BEDTIME(5),
    ;

    public companion object {
        public fun of(code: Int): GlucoseMeal? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The part of a Glucose Measurement Context (characteristic 0x2A34) a record's meal is read
 * from, as the Glucose Profile lays it out:
 *
 * - Flags (uint8). Bit 0: carbohydrate id and amount present. Bit 1: meal present. Bit 2:
 *   tester and health present. Bit 3: exercise duration and intensity present. Bit 4: medication
 *   id and amount present. Bit 5: the medication is in litres, else kilograms. Bit 6: HbA1c
 *   present. Bit 7: an extended flags byte follows the sequence number.
 * - Sequence Number (uint16), the measurement's it belongs to.
 * - Extended Flags (uint8), when bit 7 is set.
 * - Carbohydrate ID (uint8: 1 breakfast, 2 lunch, 3 dinner, 4 snack, 5 drink, 6 supper, 7 brunch)
 *   and Carbohydrate (SFLOAT, kg), when bit 0 is set.
 * - Meal (uint8: 1 preprandial, 2 postprandial, 3 fasting, 4 casual, 5 bedtime), when bit 1 is set.
 *
 * The fields after the meal say nothing of when the reading was taken and are not read.
 */
public data class GlucoseContext(
    public val sequenceNumber: Int,
    public val carbohydrateId: Int?,
    public val meal: GlucoseMeal?,
) {
    public companion object {
        /** [value] read as far as the meal, or null when it is shorter than its flags say. */
        public fun parse(value: ByteArray): GlucoseContext? {
            val fields = GattFields(value)
            val flags = fields.uint8() ?: return null
            val sequence = fields.uint16() ?: return null
            if (flags.hasBit(7) && fields.uint8() == null) return null
            var carbohydrate: Int? = null
            if (flags.hasBit(0)) {
                carbohydrate = fields.uint8() ?: return null
                fields.sfloat() ?: return null
            }
            val meal = if (flags.hasBit(1)) GlucoseMeal.of(fields.uint8() ?: return null) else null
            return GlucoseContext(sequence, carbohydrate, meal)
        }
    }
}
