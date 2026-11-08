package tech.mmarca.openwatch.link

import android.util.Log
import dagger.Lazy
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkLiveBatch
import tech.mmarca.openlink.OpenLinkLiveSubscription
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkRecordingPhase
import tech.mmarca.openlink.OpenLinkRecordingRequest
import tech.mmarca.openlink.OpenLinkRecordingState
import tech.mmarca.openlink.OpenLinkSample
import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openlink.host.OpenLinkHost
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.garmin.GarminNotificationBridge
import tech.mmarca.openwatch.devices.garmin.GarminRealtimeStore
import tech.mmarca.openwatch.devices.wearos.WearOsCompanionManager

/**
 * Recording sessions and live subscriptions from another app. Neither watch can be told to
 * start a workout, so a session keeps the watch's live readings flowing for its length: a
 * Garmin's held link with its heart-rate and steps streams on, or the Wear OS app's live
 * heart rate. A device's stream runs while any session or subscription collects it.
 */
@Singleton
class LinkLiveSessions @Inject constructor(
    private val devices: LinkDevices,
    private val deviceRepository: BleDeviceRepository,
    private val garminBridge: GarminNotificationBridge,
    private val garminRealtime: GarminRealtimeStore,
    private val wearOs: WearOsCompanionManager,
    private val host: Lazy<OpenLinkHost>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val streams = mutableMapOf<String, SharedFlow<OpenLinkSample>>()
    private val recordings = mutableMapOf<String, Job>()
    private val subscriptions = mutableMapOf<String, Job>()

    @Synchronized
    fun startRecording(request: OpenLinkRecordingRequest) {
        if (request.sessionId in recordings) return
        val targets = targets(request.deviceId)
        if (targets.isEmpty()) {
            publish(OpenLinkRecordingState(request.sessionId, request.deviceId.orEmpty(), OpenLinkRecordingPhase.FAILED, OpenLinkRecordingState.REASON_NO_DEVICE, now()))
            return
        }
        recordings[request.sessionId] = scope.launch {
            targets.forEach { device ->
                launch { followDevice(request.sessionId, device) }
            }
        }
    }

    @Synchronized
    fun stopRecording(sessionId: String) {
        val job = recordings.remove(sessionId) ?: return
        job.cancel()
        publish(OpenLinkRecordingState(sessionId, "", OpenLinkRecordingPhase.STOPPED, OpenLinkRecordingState.REASON_STOPPED_BY_CLIENT, now()))
    }

    @Synchronized
    fun subscribe(subscription: OpenLinkLiveSubscription) {
        if (subscription.sessionId in subscriptions) return
        val targets = targets(subscription.deviceId).filter { device -> subscription.types.any { it in device.liveSampleTypes } }
        if (targets.isEmpty()) return
        subscriptions[subscription.sessionId] = scope.launch {
            targets.forEach { device ->
                launch { relay(subscription, device) }
            }
        }
    }

    @Synchronized
    fun unsubscribe(sessionId: String) {
        subscriptions.remove(sessionId)?.cancel()
    }

    private fun targets(deviceId: String?): List<OpenLinkDevice> =
        devices.devices.value.filter { it.supportsRecording && (deviceId == null || it.id == deviceId) }

    /**
     * STARTING, then RECORDING on the first reading; FAILED if none comes in time, and
     * RECORDING after all if one comes later. One collection for the whole session, so the
     * watch's stream is not stopped and reopened in between.
     */
    private suspend fun followDevice(sessionId: String, device: OpenLinkDevice): Unit = coroutineScope {
        publish(OpenLinkRecordingState(sessionId, device.id, OpenLinkRecordingPhase.STARTING, null, now()))
        var reading = false
        val watchdog = launch {
            delay(FIRST_READING_TIMEOUT)
            publish(OpenLinkRecordingState(sessionId, device.id, OpenLinkRecordingPhase.FAILED, OpenLinkRecordingState.REASON_NOT_CONNECTED, now()))
        }
        stream(device).collect {
            if (!reading) {
                reading = true
                watchdog.cancel()
                publish(OpenLinkRecordingState(sessionId, device.id, OpenLinkRecordingPhase.RECORDING, null, now()))
            }
        }
    }

    /** Batches a device's samples of the asked types once a second into the subscription. */
    private suspend fun relay(subscription: OpenLinkLiveSubscription, device: OpenLinkDevice) {
        val pending = mutableListOf<OpenLinkSample>()
        val flusher = scope.launch {
            while (true) {
                delay(BATCH_MILLIS)
                val batch = synchronized(pending) { pending.toList().also { pending.clear() } }
                if (batch.isNotEmpty()) host.get().publishLive(OpenLinkLiveBatch(subscription.sessionId, device.id, batch))
            }
        }
        try {
            stream(device).collect { sample ->
                if (sample.type in subscription.types) synchronized(pending) { pending += sample }
            }
        } finally {
            flusher.cancel()
        }
    }

    @Synchronized
    private fun stream(device: OpenLinkDevice): SharedFlow<OpenLinkSample> = streams.getOrPut(device.id) {
        val source: Flow<OpenLinkSample> = when (device.integration) {
            OpenLinkIntegration.GARMIN -> garminStream(device.id)
            OpenLinkIntegration.WEAROS -> wearOsStream(device.id)
            OpenLinkIntegration.XIAOMI -> flowOf()
        }
        // Runs while anyone collects; a few seconds after the last collector goes, the watch's
        // stream stops. The grace covers a session and its subscription arriving apart.
        source.shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = STREAM_GRACE_MILLIS), replay = 0)
    }

    private fun garminStream(deviceId: String): Flow<OpenLinkSample> {
        val heartRate = garminRealtime.readings
            .distinctUntilChangedBy { it.heartRateAt }
            .map { reading -> reading.heartRateAt?.let { at -> reading.heartRateBpm?.let { OpenLinkSample(OpenLinkSampleType.HEART_RATE, at.toEpochMilli(), it.toDouble()) } } }
            .filterNotNull()
        val steps = garminRealtime.readings
            .distinctUntilChangedBy { it.stepsAt }
            .map { reading -> reading.stepsAt?.let { at -> reading.steps?.let { OpenLinkSample(OpenLinkSampleType.STEPS, at.toEpochMilli(), it.toDouble()) } } }
            .filterNotNull()
        return merge(heartRate, steps)
            .onStart { garminBridge.holdLiveForSession(deviceId, true) }
            .onCompletion { garminBridge.holdLiveForSession(deviceId, false) }
    }

    private fun wearOsStream(deviceId: String): Flow<OpenLinkSample> = flow {
        val device = deviceRepository.devices.firstOrNull { it.id == deviceId }
        emitAll(
            wearOs.liveHeartRate(device?.address, device?.bluetoothName ?: device?.displayName)
                .filterNotNull()
                .map { bpm -> OpenLinkSample(OpenLinkSampleType.HEART_RATE, now(), bpm.toDouble()) },
        )
    }

    private fun publish(state: OpenLinkRecordingState) {
        runCatching { host.get().publishRecordingState(state) }.onFailure { Log.w(TAG, "publishing a session state failed", it) }
    }

    private fun now(): Long = System.currentTimeMillis()

    private companion object {
        const val TAG = "LinkLiveSessions"
        const val BATCH_MILLIS = 1_000L
        const val FIRST_READING_TIMEOUT = 45_000L
        const val STREAM_GRACE_MILLIS = 5_000L
    }
}
