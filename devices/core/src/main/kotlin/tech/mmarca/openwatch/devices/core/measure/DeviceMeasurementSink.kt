package tech.mmarca.openwatch.devices.core.measure

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import tech.mmarca.openwatch.core.presentation.isPermissionFailure
import tech.mmarca.openwatch.data.repository.HealthRecordStore
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.data.repository.contract.DeviceStateRepository
import tech.mmarca.openwatch.data.repository.insertWithOutcomes
import tech.mmarca.openwatch.data.repository.partitionByWriteGrant
import tech.mmarca.openwatch.devices.core.DeviceLog
import tech.mmarca.openwatch.devices.core.sync.SyncFailure
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.healthconnect.HealthConnectReads
import tech.mmarca.openwatch.healthconnect.HealthConnectSyncDisabledException
import tech.mmarca.openwatch.healthconnect.isHealthConnectRateLimit

/**
 * The [MeasurementSink]: the scale's Room-before-Health-Connect pipeline, for any family.
 *
 * A measurement is kept in the device's own state (`device_state`, under [KEY_PREFIX] and its
 * key) before anything is written, so it goes with its device when the device is removed. A
 * pass reads every kept measurement back through its family's [MeasurementRecords], writes in
 * one batch through the record sink what the grants allow, and forgets what was written. One
 * whose first record's grant is missing stays kept; one that gives no record is dropped. A
 * failed write keeps them all: the ids are deterministic, so a write that half went through is
 * written again as the same records. One pass at a time.
 */
@Singleton
class DeviceMeasurementSink(
    private val registry: DeviceRegistry,
    private val state: DeviceStateRepository,
    private val store: HealthRecordStore,
    private val reads: HealthConnectReads,
    private val mappers: Map<String, MeasurementRecords>,
    private val now: () -> Instant,
) : MeasurementSink {

    @Inject
    constructor(
        registry: DeviceRegistry,
        state: DeviceStateRepository,
        store: HealthRecordStore,
        reads: HealthConnectReads,
        mappers: Map<String, @JvmSuppressWildcards MeasurementRecords>,
    ) : this(registry, state, store, reads, mappers, Instant::now)

    private val writing = Mutex()
    private val log = DeviceLog("DeviceMeasurements")
    private val _backlog = MutableStateFlow<Map<String, MeasurementOutcome>>(emptyMap())

    override val backlog: StateFlow<Map<String, MeasurementOutcome>> = _backlog.asStateFlow()

    override suspend fun accept(device: BleSensorDevice, measurements: List<PushedMeasurement>): MeasurementOutcome {
        // Kept before anything else: from here on a crash, a refusal or a cancel loses nothing.
        for (measurement in measurements) state.put(device.id, KEY_PREFIX + measurement.key, measurement.payload)
        return writePending(device)
    }

    override suspend fun retryPending(device: BleSensorDevice?): MeasurementOutcome =
        (device?.let(::listOf) ?: registry.devices)
            .map { writePending(it) }
            .fold(MeasurementOutcome()) { all, one ->
                MeasurementOutcome(
                    written = all.written + one.written,
                    pending = all.pending + one.pending,
                    withheld = all.withheld + one.withheld,
                    failure = all.failure ?: one.failure,
                )
            }

    private suspend fun writePending(device: BleSensorDevice): MeasurementOutcome = writing.withLock {
        val keys = state.keys(device.id).filter { it.startsWith(KEY_PREFIX) }.sorted()
        val outcome = when {
            keys.isEmpty() -> MeasurementOutcome()
            // A family this version has no mapping for: kept, for the version that has one.
            else -> device.integration?.storageName?.let(mappers::get)?.let { mapper -> write(device, keys, mapper) }
                ?: MeasurementOutcome(pending = keys.size)
        }
        _backlog.update { all -> if (outcome.pending == 0) all - device.id else all + (device.id to outcome) }
        outcome
    }

    private suspend fun write(device: BleSensorDevice, keys: List<String>, mapper: MeasurementRecords): MeasurementOutcome =
        try {
            val granted = reads.grantedPermissions()
            val writable = mutableListOf<Pair<String, List<Record>>>()
            val withheld = sortedSetOf<String>()
            var kept = 0
            for (key in keys) {
                val payload = state.get(device.id, key) ?: continue
                val records = recordsOf(mapper, device, PushedMeasurement(key.removePrefix(KEY_PREFIX), payload))
                val primary = records.firstOrNull()?.let { HealthPermission.getWritePermission(it::class) }
                when {
                    // Nothing in it to write: a reading the device marked unusable, or unreadable.
                    primary == null -> state.put(device.id, key, null)
                    primary !in granted -> {
                        withheld += primary
                        kept++
                    }
                    else -> writable += key to partitionByWriteGrant(records, granted).first
                }
            }
            if (writable.isNotEmpty()) {
                // Not cut short once started: a write whose keys stay would only be written again,
                // but a forgotten key whose write never happened would be lost.
                withContext(NonCancellable) {
                    store.insertWithOutcomes(writable.flatMap { it.second })
                    writable.forEach { (key, _) -> state.put(device.id, key, null) }
                }
                registry.markSynced(device.id, now())
            }
            log.log("wrote ${writable.size} measurement(s), ${kept} kept for a grant")
            MeasurementOutcome(written = writable.size, pending = kept, withheld = withheld)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.log("write failed: ${error::class.simpleName}")
            MeasurementOutcome(pending = keys.size, failure = error.toFailure())
        }

    /** The family's records for one measurement; a mapping that throws gives none, and the measurement is dropped. */
    private fun recordsOf(mapper: MeasurementRecords, device: BleSensorDevice, measurement: PushedMeasurement): List<Record> =
        try {
            mapper.records(device, measurement)
        } catch (error: IllegalArgumentException) {
            // Health Connect's record constructors refuse a value out of their range.
            log.log("a measurement gave no record: ${error::class.simpleName}")
            emptyList()
        }

    private fun Throwable.toFailure(): SyncFailure = when {
        isPermissionFailure() -> SyncFailure.HealthConnectPermission
        isHealthConnectRateLimit() -> SyncFailure.RateLimited
        this is HealthConnectSyncDisabledException -> SyncFailure.SyncPaused
        else -> SyncFailure.NotSaved
    }

    companion object {
        /** The `device_state` keys a kept measurement goes under, before its own key. Stored: do not rename. */
        const val KEY_PREFIX = "measurement."
    }
}
