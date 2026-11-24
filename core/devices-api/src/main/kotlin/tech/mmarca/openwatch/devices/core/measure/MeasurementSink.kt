package tech.mmarca.openwatch.devices.core.measure

import kotlinx.coroutines.flow.StateFlow
import tech.mmarca.openwatch.devices.core.sync.SyncFailure
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/**
 * One measurement a device pushed, as its family keeps it until Health Connect has it.
 *
 * [key] names the measurement for good: the same reading handed over again has the same key, so
 * it is kept once and written once, and the family builds its record ids from it. [payload] is
 * the family's own text, which its `MeasurementRecords` reads back into records, at once or on
 * a later try. Both are stored: their formats are final once shipped.
 */
data class PushedMeasurement(val key: String, val payload: String) {
    init {
        require(key.isNotBlank()) { "a measurement has a key" }
    }
}

/** What a [MeasurementSink] pass did for one device. */
data class MeasurementOutcome(
    /** Measurements that reached Health Connect in this pass. */
    val written: Int = 0,
    /** Measurements still kept for a later pass. */
    val pending: Int = 0,
    /** The write grants a pending measurement waits for; empty when none is missing. */
    val withheld: Set<String> = emptySet(),
    /** Why a write failed, when one did; what was written stays written. */
    val failure: SyncFailure? = null,
)

/**
 * Where data a device pushes goes: a measurement a cuff indicates, a scale hands over or a strap
 * streams. The other way in is a sync, which the app pulls (`DeviceSyncPort`).
 *
 * Durable first, Health Connect after: [accept] keeps every measurement for its device before it
 * writes, so one that cannot be written now (a grant missing, sync paused, a background write
 * that cannot ask) is tried again by the next [accept], by [retryPending] and at the next start.
 * What is kept goes with its device when the device is removed. Never throws, bar cancellation.
 */
interface MeasurementSink {

    /** Keeps [measurements] for [device], then writes everything the device has pending. */
    suspend fun accept(device: BleSensorDevice, measurements: List<PushedMeasurement>): MeasurementOutcome

    /** Writes what is pending for [device], or for every registered device when null. */
    suspend fun retryPending(device: BleSensorDevice? = null): MeasurementOutcome

    /** What the last pass left pending, by device id; absent when nothing is. */
    val backlog: StateFlow<Map<String, MeasurementOutcome>>
}
