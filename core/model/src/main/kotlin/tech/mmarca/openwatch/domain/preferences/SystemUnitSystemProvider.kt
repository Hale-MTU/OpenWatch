package tech.mmarca.openwatch.domain.preferences

import java.util.Locale

/**
 * Maps a BCP-47 `-u-ms-` token or an ICU MeasurementSystem name to a unit
 * system. UK maps to metric: it is metric-first for everything shown here.
 */
fun unitSystemForMeasurementSystem(measurementSystem: String?): UnitSystem? =
    when (measurementSystem?.lowercase(Locale.ROOT)) {
        "ussystem", "us" -> UnitSystem.IMPERIAL
        "metric", "si", "uksystem", "uk" -> UnitSystem.METRIC
        else -> null
    }

/**
 * The single seam through which a SYSTEM choice becomes a [UnitSystem]. The app's
 * implementation reads the platform's locale data (`core/presentation/LocaleUnitSystemProvider`).
 */
fun interface SystemUnitSystemProvider {

    fun current(): UnitSystem
}
