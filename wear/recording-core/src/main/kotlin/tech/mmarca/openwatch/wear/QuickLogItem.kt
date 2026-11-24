package tech.mmarca.openwatch.wear

import tech.mmarca.openwatch.wearlink.WearLinkEntry

/**
 * One entry the quick log can offer, by the entry type it logs. The wearer
 * chooses which show and in which order (`WearQuickLogPreferences`); the
 * names are stored ([Choices]), so an item is never renamed. The screens
 * give each its name, icon and colour.
 */
enum class QuickLogItem(val type: WearLinkEntry.Type) {
    WATER(WearLinkEntry.Type.WATER),
    WEIGHT(WearLinkEntry.Type.WEIGHT),
    BLOOD_PRESSURE(WearLinkEntry.Type.BLOOD_PRESSURE),
    BODY_FAT(WearLinkEntry.Type.BODY_FAT),
    BLOOD_OXYGEN(WearLinkEntry.Type.BLOOD_OXYGEN),
    RESPIRATORY_RATE(WearLinkEntry.Type.RESPIRATORY_RATE),
    BODY_TEMPERATURE(WearLinkEntry.Type.BODY_TEMPERATURE),
    BLOOD_GLUCOSE(WearLinkEntry.Type.BLOOD_GLUCOSE),
    HEIGHT(WearLinkEntry.Type.HEIGHT),

    /** The guided breathing minute, logged as a mindful session. */
    BREATHE(WearLinkEntry.Type.MINDFULNESS),
    ;

    companion object {
        /** Every item, in the order the quick log listed them before the wearer chose. */
        val Defaults: List<QuickLogItem> = entries.toList()

        fun byName(name: String): QuickLogItem? = entries.firstOrNull { it.name == name }
    }
}
