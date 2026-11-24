package tech.mmarca.openwatch.wear

/**
 * Same two values as the phone app's `UnitSystem`. The wearer's choice on the
 * watch ([WatchPreferences.unitSystem]); the names are stored, so they are
 * never renamed. Readings stay metric; only their display changes.
 */
enum class UnitSystem {
    METRIC,
    IMPERIAL,
    ;

    /** [millilitres] of water in this system's unit: millilitres, or US fluid ounces. */
    fun water(millilitres: Double): Double = when (this) {
        METRIC -> millilitres
        IMPERIAL -> millilitres / MILLILITRES_PER_FLUID_OUNCE
    }

    private companion object {
        const val MILLILITRES_PER_FLUID_OUNCE = 29.5735
    }
}
