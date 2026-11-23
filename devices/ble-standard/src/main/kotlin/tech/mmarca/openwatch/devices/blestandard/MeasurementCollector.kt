package tech.mmarca.openwatch.devices.blestandard

import java.time.Instant
import java.time.ZoneId
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tech.mmarca.openwatch.core.performance.ApplicationScope
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.data.repository.contract.DeviceStateRepository
import tech.mmarca.openwatch.devices.core.DeviceLog
import tech.mmarca.openwatch.devices.core.RadioLeaseOwner
import tech.mmarca.openwatch.devices.core.measure.MeasurementOutcome
import tech.mmarca.openwatch.devices.core.measure.MeasurementSink
import tech.mmarca.openwatch.devices.core.measure.PushedMeasurement
import tech.mmarca.openwatch.devices.core.pairing.CompanionPresenceObserver
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/**
 * The devices that hand over what they measured when they wake: a blood pressure monitor, a
 * glucose meter, a scale. Android tells the app the associated device is in range (the
 * companion presence every family shares); the collector dials it under the `SYNC` lease,
 * switches its measurement characteristic on, takes what it sends (a glucose meter is asked for
 * its stored records through the Record Access Control Point, from the one after the last it
 * gave), and hands every reading to the [MeasurementSink], kept as the bytes the device sent.
 * One collection per device at a time.
 *
 * A device that sends a user id is taken to be one person's: the first id it sends is kept, and a
 * reading of another user is someone else's and is left out.
 */
@Singleton
class MeasurementCollector(
    private val radio: ProfileRadio,
    private val sink: MeasurementSink,
    private val registry: DeviceRegistry,
    private val state: DeviceStateRepository,
    private val scope: CoroutineScope,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
) : CompanionPresenceObserver {

    @Inject
    constructor(
        radio: ProfileRadio,
        sink: MeasurementSink,
        registry: DeviceRegistry,
        state: DeviceStateRepository,
        @ApplicationScope scope: CoroutineScope,
    ) : this(radio, sink, registry, state, scope, Instant::now, ZoneId::systemDefault)

    private val log = DeviceLog("BleStandard")
    private val running = Collections.synchronizedSet(mutableSetOf<String>())

    override fun onCompanionDeviceAppeared(address: String) {
        val device = registry.devices.firstOrNull {
            it.enabled && it.address.equals(address, ignoreCase = true) && PushProfile.of(it) != null
        } ?: return
        collect(device)
    }

    /** Collects from [device] on the app's scope, unless a collection from it already runs. */
    fun collect(device: BleSensorDevice): Job? {
        if (!running.add(device.id)) return null
        return scope.launch {
            try {
                collectNow(device)
            } finally {
                running.remove(device.id)
            }
        }
    }

    /** One collection: the link, what it brought, and the sink. What came before a drop is kept. */
    suspend fun collectNow(device: BleSensorDevice): MeasurementOutcome {
        val profile = PushProfile.of(device) ?: return MeasurementOutcome()
        val received = mutableListOf<PushedMeasurement>()
        var lastSequence: Int? = null
        try {
            radio.withLink(device.address, RadioLeaseOwner.SYNC) { link ->
                lastSequence = when (profile) {
                    PushProfile.GLUCOSE -> readGlucose(device, link, received)
                    else -> {
                        readIndicated(device, profile, link, received)
                        null
                    }
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Asleep again, out of range, the link dropped: what came is kept, and the next wake brings the rest.
            log.log("collection ended: ${error::class.simpleName}")
        }
        log.log("${profile.name}: ${received.size} reading(s)")
        return withContext(NonCancellable) {
            val outcome = sink.accept(device, received)
            // Kept by the sink now, written or waiting: the meter need not send them again.
            lastSequence?.let { state.put(device.id, KEY_GLUCOSE_SEQUENCE, it.toString()) }
            outcome
        }
    }

    /** A cuff or a scale: its measurement switched on, then every value it indicates until it falls quiet. */
    private suspend fun readIndicated(
        device: BleSensorDevice,
        profile: PushProfile,
        link: ProfileLink,
        received: MutableList<PushedMeasurement>,
    ) {
        link.enable(profile.service, profile.measurement, indicate = true)
        link.receiveUntilQuiet { value ->
            if (value.characteristic == profile.measurement) {
                profile.pushed(value.bytes, now(), zone())?.takeIf { (_, user) -> isThisPerson(device, user) }
                    ?.let { (measurement, _) -> received += measurement }
            }
            false
        }
    }

    /**
     * A glucose meter: its records and their contexts switched on, the control point too, then
     * the stored records asked for, from the one after the last it gave, until it answers.
     * Returns the highest sequence number it sent.
     */
    private suspend fun readGlucose(device: BleSensorDevice, link: ProfileLink, received: MutableList<PushedMeasurement>): Int? {
        val service = PushProfile.GLUCOSE.service
        link.enable(service, PushProfile.GLUCOSE.measurement, indicate = false)
        val hasContext = link.has(service, StandardProfiles.GLUCOSE_CONTEXT)
        if (hasContext) link.enable(service, StandardProfiles.GLUCOSE_CONTEXT, indicate = false)
        link.enable(service, StandardProfiles.RECORD_ACCESS_CONTROL_POINT, indicate = true)
        val from = state.get(device.id, KEY_GLUCOSE_SEQUENCE)?.toIntOrNull()?.let { it + 1 }
        val records = linkedMapOf<Int, ByteArray>()
        val contexts = mutableMapOf<Int, ByteArray>()
        link.write(
            service,
            StandardProfiles.RECORD_ACCESS_CONTROL_POINT,
            from?.takeIf { it <= MAX_SEQUENCE }?.let(RecordAccessControlPoint::reportFrom) ?: RecordAccessControlPoint.reportAll(),
        )
        link.receiveUntilQuiet { value ->
            when (value.characteristic) {
                PushProfile.GLUCOSE.measurement ->
                    GlucoseMeasurement.parse(value.bytes)?.let { records[it.sequenceNumber] = value.bytes }
                StandardProfiles.GLUCOSE_CONTEXT ->
                    GlucoseContext.parse(value.bytes)?.let { contexts[it.sequenceNumber] = value.bytes }
            }
            // The answer to the request ends it: a success, no records, or a refusal.
            value.characteristic == StandardProfiles.RECORD_ACCESS_CONTROL_POINT &&
                RecordAccessControlPoint.reportResponse(value.bytes) != null
        }
        val at = now()
        val offset = zone().rules.getOffset(at).totalSeconds
        records.forEach { (sequence, bytes) ->
            val measurement = GlucoseMeasurement.parse(bytes) ?: return@forEach
            val kept = ReceivedValue(at.toEpochMilli(), offset, bytes, contexts[sequence])
            received += PushedMeasurement(PushProfile.glucoseKey(measurement), kept.encode())
        }
        return records.keys.maxOrNull()
    }

    /** Whether a reading of [userId] is this person's: the first user a device names is. No id is anyone's. */
    private fun isThisPerson(device: BleSensorDevice, userId: Int?): Boolean {
        if (userId == null || userId == UNKNOWN_USER) return true
        val kept = state.get(device.id, KEY_USER)?.toIntOrNull()
        if (kept == null) state.put(device.id, KEY_USER, userId.toString())
        return kept == null || kept == userId
    }

    /**
     * Every value the link brings to [onValue] until it returns true, the device falls quiet for
     * [QUIET_MILLIS], [MAX_MILLIS] pass, or the link ends: a device that hands over and hangs up
     * is done, not failed.
     */
    private suspend fun ProfileLink.receiveUntilQuiet(onValue: (CharacteristicValue) -> Boolean) {
        withTimeoutOrNull(MAX_MILLIS) {
            coroutineScope {
                val channel: ReceiveChannel<CharacteristicValue> = values.produceIn(this)
                try {
                    while (true) {
                        val next = withTimeoutOrNull(QUIET_MILLIS) { channel.receiveCatching() } ?: break
                        val value = next.getOrNull() ?: break
                        if (onValue(value)) break
                    }
                } finally {
                    channel.cancel()
                }
            }
        }
    }

    companion object {
        /** The `device_state` key of the last glucose record a meter gave. Stored: do not rename. */
        const val KEY_GLUCOSE_SEQUENCE = "glucose.last_sequence"

        /** The `device_state` key of the user a cuff's or scale's readings are kept for. Stored: do not rename. */
        const val KEY_USER = "user_id"

        private const val UNKNOWN_USER = 0xFF
        private const val MAX_SEQUENCE = 0xFFFF
        private const val QUIET_MILLIS = 10_000L
        private const val MAX_MILLIS = 90_000L
    }
}
