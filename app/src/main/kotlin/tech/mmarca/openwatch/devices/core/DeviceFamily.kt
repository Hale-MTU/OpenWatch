package tech.mmarca.openwatch.devices.core

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/** What a registered device can do: asked of its family, never decided by naming a vendor. */
enum class DeviceFeature {
    /** Pulled and written to Health Connect on a tap, over OpenLink, and on a schedule. */
    SYNC,

    /** Made to ring or vibrate from the phone. */
    FIND,

    /** Streams heart rate as it is measured. */
    LIVE_HEART_RATE,

    /** Streams the day's steps as they are counted. */
    LIVE_STEPS,
}

enum class LiveReadingKind { HEART_RATE, STEPS }

/** One reading as a device measured it, at [atMillis]. */
data class LiveReading(val kind: LiveReadingKind, val atMillis: Long, val value: Double)

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

    /** [device]'s readings as they are measured, while collected; the kinds its LIVE features name. */
    fun liveReadings(device: BleSensorDevice): Flow<LiveReading> = emptyFlow()

    /** Whether each of the family's devices has its link up now, by device id; absent is not known. */
    val connections: Flow<Map<String, Boolean>> get() = flowOf(emptyMap())

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

    /** Every family's [DeviceFamily.connections], in one map by device id. */
    val connections: Flow<Map<String, Boolean>> =
        combine(byId.values.map { it.connections }) { maps -> maps.fold(emptyMap()) { all, map -> all + map } }

    fun offersHistoryDeletion(device: BleSensorDevice): Boolean = of(device)?.offersHistoryDeletion(device) == true

    suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean) {
        of(device)?.forget(device, deleteHistory)
    }
}
