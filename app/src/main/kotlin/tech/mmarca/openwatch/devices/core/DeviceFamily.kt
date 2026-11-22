package tech.mmarca.openwatch.devices.core

import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/** What a registered device can do: asked of its family, never decided by naming a vendor. */
enum class DeviceFeature {
    /** Pulled and written to Health Connect on a tap, over OpenLink, and on a schedule. */
    SYNC,

    /** Made to ring or vibrate from the phone. */
    FIND,

    /** Streams heart rate as it is measured. */
    LIVE_HEART_RATE,
}

/**
 * One family of devices, as the rest of the app sees it: which registered devices are its own,
 * what they can do, and how the app lets one go. Each binds itself into [DeviceFamilies] by [id]
 * in `DevicesModule`; a new family is a package and that binding.
 */
interface DeviceFamily {

    /** The family's persisted name: the devices' `integration`. */
    val id: String

    fun owns(device: BleSensorDevice): Boolean

    fun features(device: BleSensorDevice): Set<DeviceFeature>

    /** Whether removing [device] can also delete history the family keeps on the phone. */
    fun offersHistoryDeletion(device: BleSensorDevice): Boolean = false

    /**
     * Lets go of what the family holds for [device] once its registry row is gone: its state,
     * keys, the system's bond or association, and with [deleteHistory] the history
     * [offersHistoryDeletion] named. Best effort: nothing here is thrown.
     */
    suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean)
}

/** Every [DeviceFamily] the app knows, by id. */
@Singleton
class DeviceFamilies @Inject constructor(
    private val byId: Map<String, @JvmSuppressWildcards DeviceFamily>,
) {
    init {
        byId.forEach { (key, family) -> require(key == family.id) { "the family bound as $key calls itself ${family.id}" } }
    }

    /** The family that owns [device]; null for a plain sensor or a family this version does not know. */
    fun of(device: BleSensorDevice): DeviceFamily? = byId.values.firstOrNull { it.owns(device) }

    fun features(device: BleSensorDevice): Set<DeviceFeature> = of(device)?.features(device).orEmpty()

    fun offersHistoryDeletion(device: BleSensorDevice): Boolean = of(device)?.offersHistoryDeletion(device) == true

    suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean) {
        of(device)?.forget(device, deleteHistory)
    }
}
