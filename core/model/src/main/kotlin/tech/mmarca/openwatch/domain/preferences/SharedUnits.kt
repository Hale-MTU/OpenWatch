package tech.mmarca.openwatch.domain.preferences

/**
 * The units an app connected over OpenLink decides: OpenVitals' choice. OpenWatch's own choice
 * gives way to it while that app is installed and allowed to share settings. The link host keeps
 * it and implements this, so nothing outside it sees an OpenLink type. Read from memory.
 */
fun interface SharedUnits {

    /** The connected app's choice, with the app's name; null while no app decides. */
    fun decided(): SharedUnitChoice?

    companion object {
        /** No connected app decides: OpenWatch's own choice holds. */
        val NONE: SharedUnits = SharedUnits { null }
    }
}

/** A unit system a connected app chose, and that app's name, for "set in OpenVitals". */
data class SharedUnitChoice(val preference: UnitSystemPreference, val appLabel: String)
