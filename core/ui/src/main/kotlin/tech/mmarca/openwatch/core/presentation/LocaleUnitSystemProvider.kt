package tech.mmarca.openwatch.core.presentation

import android.icu.util.LocaleData
import android.icu.util.ULocale
import android.os.Build
import java.util.Locale
import tech.mmarca.openwatch.domain.preferences.SystemUnitSystemProvider
import tech.mmarca.openwatch.domain.preferences.UnitSystem
import tech.mmarca.openwatch.domain.preferences.unitSystemForMeasurementSystem

/** `-u-ms-` first, then ICU, then the country fallback (the test JVM stubs ICU). */
object LocaleUnitSystemProvider : SystemUnitSystemProvider {

    override fun current(): UnitSystem {
        val locale = Locale.getDefault()
        return unitSystemForMeasurementSystem(locale.getUnicodeLocaleType("ms"))
            ?: icuMeasurementSystem(locale)
            ?: countryFallback(locale)
    }

    private fun icuMeasurementSystem(locale: Locale): UnitSystem? {
        // LocaleData.getMeasurementSystem exists from API 28.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            when (LocaleData.getMeasurementSystem(ULocale.forLocale(locale))) {
                LocaleData.MeasurementSystem.US -> UnitSystem.IMPERIAL
                // SI, and UK as metric-first.
                else -> UnitSystem.METRIC
            }
        }.getOrNull()
    }

    private val ImperialCountries = setOf("US", "LR", "MM")

    private fun countryFallback(locale: Locale): UnitSystem =
        if (locale.country.uppercase(Locale.US) in ImperialCountries) {
            UnitSystem.IMPERIAL
        } else {
            UnitSystem.METRIC
        }
}
