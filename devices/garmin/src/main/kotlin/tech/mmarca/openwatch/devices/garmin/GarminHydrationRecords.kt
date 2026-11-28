package tech.mmarca.openwatch.devices.garmin

import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Volume
import java.time.Instant
import java.time.ZoneOffset
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONException
import org.json.JSONObject
import tech.mmarca.openwatch.devices.core.measure.MeasurementRecords
import tech.mmarca.openwatch.devices.core.measure.PushedMeasurement
import tech.mmarca.openwatch.devices.core.saltedRecordId
import tech.mmarca.openwatch.devices.garmin.wellness.GarminWatchDevice
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/**
 * A drink logged in the watch's hydration widget, as the sink kept it, to Health Connect: one
 * [HydrationRecord] of a second at the drink's time, entered by hand on a Garmin watch, its id
 * the measurement's key salted by the watch, so a drink handed over again is the same record.
 * What a later subtraction left of it comes with a higher version, which replaces it.
 */
@Singleton
class GarminHydrationRecords @Inject constructor() : MeasurementRecords {

    override fun records(device: BleSensorDevice, measurement: PushedMeasurement): List<Record> {
        if (!measurement.key.startsWith(KEY_PREFIX)) return emptyList()
        val drink = Drink.decode(measurement.payload) ?: return emptyList()
        val at = Instant.ofEpochMilli(drink.atMillis)
        val offset = ZoneOffset.ofTotalSeconds(drink.offsetSeconds)
        return listOf(
            HydrationRecord(
                startTime = at,
                startZoneOffset = offset,
                endTime = at.plusSeconds(1),
                endZoneOffset = offset,
                volume = Volume.milliliters(drink.milliliters),
                metadata = Metadata.manualEntry(
                    device = GarminWatchDevice,
                    clientRecordId = saltedRecordId(measurement.key, device.idSalt),
                    clientRecordVersion = drink.version,
                ),
            ),
        )
    }

    /** What a kept drink holds. Stored: the fields' names are final once shipped. */
    data class Drink(val atMillis: Long, val offsetSeconds: Int, val milliliters: Double, val version: Long) {

        fun encode(): String = JSONObject()
            .put(FIELD_AT, atMillis)
            .put(FIELD_OFFSET, offsetSeconds)
            .put(FIELD_ML, milliliters)
            .put(FIELD_VERSION, version)
            .toString()

        companion object {
            private const val FIELD_AT = "at"
            private const val FIELD_OFFSET = "offset"
            private const val FIELD_ML = "ml"
            private const val FIELD_VERSION = "version"

            /** The drink in [payload], or null for one this version cannot read. */
            fun decode(payload: String): Drink? = try {
                val json = JSONObject(payload)
                Drink(json.getLong(FIELD_AT), json.getInt(FIELD_OFFSET), json.getDouble(FIELD_ML), json.getLong(FIELD_VERSION))
            } catch (_: JSONException) {
                null
            }
        }
    }

    companion object {
        /** A drink's measurement key, before its time in epoch milliseconds; its record id too. Stored: do not rename. */
        const val KEY_PREFIX = "garmin_hydration_"
    }
}
