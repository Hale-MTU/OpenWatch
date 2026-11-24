package tech.mmarca.openwatch.devices.blestandard

import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.pow

/**
 * The IEEE 11073-20601 SFLOAT the health profiles carry their values in: a 16-bit word, a 4-bit
 * signed exponent (base 10) over a 12-bit signed mantissa. The specification reserves five
 * words, all with exponent 0 (0x07FF NaN, 0x0800 NRes, 0x07FE +INFINITY, 0x0802 -INFINITY,
 * 0x0801 reserved), none of which is a reading: each reads as [Double.NaN].
 */
public fun sfloat(word: Int): Double {
    val raw = word and 0xFFFF
    if (raw in RESERVED_SFLOATS) return Double.NaN
    val mantissaBits = raw and 0x0FFF
    val mantissa = if (mantissaBits >= 0x0800) mantissaBits - 0x1000 else mantissaBits
    val exponentBits = raw shr 12
    val exponent = if (exponentBits >= 0x8) exponentBits - 0x10 else exponentBits
    return mantissa * 10.0.pow(exponent)
}

private val RESERVED_SFLOATS = 0x07FE..0x0802

/** The next SFLOAT ([sfloat]); null past the end. */
internal fun GattFields.sfloat(): Double? = uint16()?.let { sfloat(it) }

/**
 * The Date Time characteristic's layout (0x2A08), which the measurements embed: year (uint16,
 * 1582 to 9999, 0 when not known), month (1 to 12, 0 not known), day (1 to 31, 0 not known),
 * hours, minutes, seconds. The device's own wall clock, with no zone.
 */
public data class GattDateTime(
    public val year: Int,
    public val month: Int,
    public val day: Int,
    public val hours: Int,
    public val minutes: Int,
    public val seconds: Int,
) {
    /** The time it names, or null when a part is not known or out of range (a clock never set). */
    public fun toLocalDateTime(): LocalDateTime? {
        if (year == 0 || month == 0 || day == 0) return null
        return try {
            LocalDateTime.of(year, month, day, hours, minutes, seconds)
        } catch (_: java.time.DateTimeException) {
            null
        }
    }

    /** The fields as one stable word, `yyyyMMddHHmmss`: part of a measurement's identity, whatever the zone. */
    public val stamp: String
        get() = "%04d%02d%02d%02d%02d%02d".format(Locale.ROOT, year, month, day, hours, minutes, seconds)
}

internal fun GattFields.dateTime(): GattDateTime? {
    val year = uint16() ?: return null
    val month = uint8() ?: return null
    val day = uint8() ?: return null
    val hours = uint8() ?: return null
    val minutes = uint8() ?: return null
    val seconds = uint8() ?: return null
    return GattDateTime(year, month, day, hours, minutes, seconds)
}
