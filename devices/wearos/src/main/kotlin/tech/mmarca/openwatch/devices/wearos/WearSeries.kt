package tech.mmarca.openwatch.devices.wearos

import androidx.health.connect.client.records.Record
import java.time.Instant
import java.time.ZoneId
import tech.mmarca.healthcalculations.model.WearSleepMinute
import tech.mmarca.openwatch.devices.core.sync.HealthSeries
import tech.mmarca.openwatch.wearlink.WearLinkEntries
import tech.mmarca.openwatch.wearlink.WearLinkEntry
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * One series the watch keeps one line per value, as the phone pulls it with `SINCE`: the key the
 * watch stores and announces it under, how one of its lines reads, and the time of a value. A
 * series whose values are written one record each also says how ([writes]); the others have a
 * pass of their own in the sync, because a record there is an estimate over many values.
 */
class WearSeries<T : Any>(
    val key: String,
    val parse: (String) -> T?,
    val timeOf: (T) -> Instant,
    val writes: Writes<T>? = null,
) {
    /** One Health Connect record per value: the grant it needs, and the record, or null for a value Health Connect would refuse. */
    class Writes<T>(
        val label: String,
        val healthSeries: HealthSeries,
        val permission: String,
        val record: (T, ZoneId, salt: String) -> Record?,
    )

    override fun toString(): String = "WearSeries($key)"
}

/** Every series the phone pulls from the watch, by the watch's key. */
object WearOsSeries {

    val HEART_RATE = WearSeries(
        key = WearLinkProtocol.CAP_HEART_RATE,
        parse = { line -> WearLinkProtocol.parseSample(line)?.let { WearOsHeartRateSample(Instant.ofEpochMilli(it.epochMillis), it.bpm) } },
        timeOf = { it.time },
    )

    val SLEEP_MINUTES = WearSeries<WearSleepMinute>(
        key = WearLinkProtocol.CAP_SLEEP_MINUTES,
        parse = { line -> WearLinkProtocol.parseSleepMinute(line)?.let(WearOsSleepMinuteMapping::toDomain) },
        timeOf = { it.time },
    )

    val STEPS = WearSeries(
        key = WearLinkProtocol.CAP_STEPS,
        parse = { line -> WearLinkProtocol.parseStepMinute(line)?.let { WearOsStepMinute(Instant.ofEpochMilli(it.epochMillis), it.steps) } },
        timeOf = { it.start },
    )

    val ASCENT = WearSeries(
        key = WearLinkProtocol.CAP_ASCENT,
        parse = { line -> WearLinkProtocol.parseAscentMinute(line)?.let { WearOsAscentMinute(Instant.ofEpochMilli(it.epochMillis), it.decimetres / 10.0) } },
        timeOf = { it.start },
    )

    val SKIN_TEMPERATURE = WearSeries(
        key = WearLinkProtocol.CAP_SKIN_TEMPERATURE,
        parse = { line -> WearLinkProtocol.parseSkinMinute(line)?.let { WearOsSkinMinute(Instant.ofEpochMilli(it.epochMillis), it.skinCelsius) } },
        timeOf = { it.time },
    )

    val HRV = WearSeries(
        key = WearLinkProtocol.CAP_HRV,
        parse = { line -> WearLinkProtocol.parseHrvWindow(line)?.let { WearOsHrvWindow(Instant.ofEpochMilli(it.epochMillis), it.rmssdMillis, it.beats) } },
        timeOf = { it.start },
        writes = WearSeries.Writes("hrv", HealthSeries.HEART_RATE_VARIABILITY, WearOsHrvImport.writePermission, WearOsHrvImport::record),
    )

    val RESPIRATORY_RATE = WearSeries(
        key = WearLinkProtocol.CAP_RESPIRATORY_RATE,
        parse = { line ->
            WearLinkProtocol.parseRespiratoryWindow(line)?.let { WearOsRespiratoryWindow(Instant.ofEpochMilli(it.epochMillis), it.breathsPerMinute, it.routes) }
        },
        timeOf = { it.start },
        writes = WearSeries.Writes("breathing", HealthSeries.RESPIRATORY_RATE, WearOsRespiratoryImport.writePermission, WearOsRespiratoryImport::record),
    )

    val SPO2 = WearSeries(
        key = WearLinkProtocol.CAP_SPO2,
        parse = { line -> WearLinkProtocol.parseSpo2(line)?.let { WearOsSpo2Measurement(Instant.ofEpochMilli(it.epochMillis), it.percent) } },
        timeOf = { it.time },
        writes = WearSeries.Writes("spo2", HealthSeries.OXYGEN_SATURATION, WearOsSpo2Import.writePermission, WearOsSpo2Import::record),
    )

    /**
     * The entries made on the watch, by hand or by a spot measurement ([WearLinkEntries]): several
     * record types under several grants, so a pass of their own ([WearOsEntrySync]). A type this
     * build does not know does not parse, and is skipped.
     */
    val ENTRIES = WearSeries<WearLinkEntry>(
        key = WearLinkProtocol.CAP_ENTRIES,
        parse = WearLinkEntries::parse,
        timeOf = { Instant.ofEpochMilli(it.epochMillis) },
    )

    val ALL: List<WearSeries<*>> = listOf(HEART_RATE, SLEEP_MINUTES, STEPS, ASCENT, SKIN_TEMPERATURE, HRV, RESPIRATORY_RATE, SPO2, ENTRIES)
}
