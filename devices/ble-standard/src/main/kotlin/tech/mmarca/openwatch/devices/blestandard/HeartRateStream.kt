package tech.mmarca.openwatch.devices.blestandard

import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.core.performance.ApplicationScope
import tech.mmarca.openwatch.data.repository.contract.DeviceRegistry
import tech.mmarca.openwatch.devices.core.DeviceLog
import tech.mmarca.openwatch.devices.core.LiveReading
import tech.mmarca.openwatch.devices.core.LiveReadingKind
import tech.mmarca.openwatch.devices.core.RadioLeaseOwner
import tech.mmarca.openwatch.devices.core.measure.MeasurementSink
import tech.mmarca.openwatch.devices.core.measure.PushedMeasurement
import tech.mmarca.openwatch.domain.model.BleSensorDevice

/**
 * A heart rate strap's stream, one link per strap however many collect it (the device screen, an
 * app over OpenLink): the link is up while someone collects and a few seconds after, redialled
 * while it is collected if it drops. Each usable measurement is a live reading; a clock minute's
 * readings go to the [MeasurementSink] as one run, and so does the minute under way when the
 * stream stops, so what the strap measured reaches Health Connect.
 */
@Singleton
class HeartRateStream(
    private val radio: ProfileRadio,
    private val sink: MeasurementSink,
    private val registry: DeviceRegistry,
    private val scope: CoroutineScope,
    private val now: () -> Instant,
    private val zone: () -> ZoneId,
) {
    @Inject
    constructor(
        radio: ProfileRadio,
        sink: MeasurementSink,
        registry: DeviceRegistry,
        @ApplicationScope scope: CoroutineScope,
    ) : this(radio, sink, registry, scope, Instant::now, ZoneId::systemDefault)

    private val log = DeviceLog("BleStandard")
    private val streams = mutableMapOf<String, Flow<LiveReading>>()
    private val _connections = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    /** Whether each strap's link is up now, by device id. */
    val connections: StateFlow<Map<String, Boolean>> = _connections.asStateFlow()

    fun readings(device: BleSensorDevice): Flow<LiveReading> = synchronized(streams) {
        streams.getOrPut(device.id) {
            flow { stream(device) }
                // Off the chest, out of range or Bluetooth off: dialled again while someone still collects.
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) return@retryWhen false
                    log.log("strap link ended: ${cause::class.simpleName}")
                    delay(REDIAL_DELAY_MILLIS)
                    true
                }
                .shareIn(scope, SharingStarted.WhileSubscribed(STOP_GRACE_MILLIS), replay = 0)
        }
    }

    /** Lets go of a removed strap's stream; its link ends with its last collector. */
    fun forget(deviceId: String) {
        synchronized(streams) { streams.remove(deviceId) }
        _connections.update { it - deviceId }
    }

    private suspend fun FlowCollector<LiveReading>.stream(device: BleSensorDevice) {
        val minutes = HeartRateMinutes { zone().rules.getOffset(now()).totalSeconds }
        try {
            radio.withLink(device.address, RadioLeaseOwner.LIVE) { link ->
                _connections.update { it + (device.id to true) }
                readBattery(device, link)
                link.enable(StandardProfiles.HEART_RATE_SERVICE, StandardProfiles.HEART_RATE_MEASUREMENT, indicate = false)
                link.values.collect { value ->
                    if (value.characteristic != StandardProfiles.HEART_RATE_MEASUREMENT) return@collect
                    val measurement = HeartRateMeasurement.parse(value.bytes)?.takeIf { it.isUsable } ?: return@collect
                    val at = now().toEpochMilli()
                    emit(LiveReading(LiveReadingKind.HEART_RATE, at, measurement.beatsPerMinute.toDouble()))
                    minutes.add(HeartRateSample(at, measurement.beatsPerMinute))?.let { push(device, it) }
                }
            }
        } finally {
            _connections.update { it + (device.id to false) }
            minutes.drain()?.let { push(device, it) }
        }
    }

    /** The strap's battery, when it has the Battery Service. A read that fails is no reading. */
    private suspend fun readBattery(device: BleSensorDevice, link: ProfileLink) {
        if (!link.has(StandardProfiles.BATTERY_SERVICE, StandardProfiles.BATTERY_LEVEL)) return
        val level = try {
            link.read(StandardProfiles.BATTERY_SERVICE, StandardProfiles.BATTERY_LEVEL)?.firstOrNull()?.toInt()?.and(0xFF)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            log.log("battery read failed: ${error::class.simpleName}")
            null
        }
        level?.takeIf { it in 0..100 }?.let { registry.updateBatteryLevel(device.id, it) }
    }

    /** Hands a finished run to the sink on the app's scope: the stream may be ending under it. */
    private fun push(device: BleSensorDevice, run: HeartRateSamples) {
        scope.launch { sink.accept(device, listOf(PushedMeasurement(run.key, run.encode()))) }
    }

    private companion object {
        const val REDIAL_DELAY_MILLIS = 10_000L

        /** Keeps the link through a rotation and a session and its subscription arriving apart. */
        const val STOP_GRACE_MILLIS = 5_000L
    }
}
