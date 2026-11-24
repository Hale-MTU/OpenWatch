package tech.mmarca.openwatch.devices.core.measure

import androidx.health.connect.client.records.Record
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/**
 * How a family's pushed measurements become Health Connect records: the family's own mapping,
 * bound by its persisted name (`@IntoMap @StringKey`) in its Hilt module, which the
 * [MeasurementSink] reads a kept measurement back through, at once or on a later try.
 */
fun interface MeasurementRecords {

    /**
     * The records [measurement] gives for [device], the one the measurement stands for first:
     * without that one's write grant the measurement is kept for later; the others are written
     * where granted and dropped where not. Empty for a payload that gives nothing (a reading the
     * device marked unusable, a payload this version cannot read): it is dropped. Every record
     * carries a deterministic `clientRecordId` salted by the device ([tech.mmarca.openwatch.devices.core.saltedRecordId]).
     */
    fun records(device: BleSensorDevice, measurement: PushedMeasurement): List<Record>
}
