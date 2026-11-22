package tech.mmarca.openwatch.link

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import tech.mmarca.openlink.OpenLinkConnection
import tech.mmarca.openlink.OpenLinkDevice
import tech.mmarca.openlink.OpenLinkDeviceKind
import tech.mmarca.openlink.OpenLinkIntegration
import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.DeviceFamilies
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.xiaomi.ScaleListenerStatus
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleConfig
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleListener
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleStore
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncController
import tech.mmarca.openwatch.core.performance.ApplicationScope

/**
 * The paired devices as OpenLink shows them: the registered devices a family owns, with what
 * the family says they can do and whether their link is up, the sync state, and the scale.
 * Kept as a StateFlow so the binder thread reads it from memory.
 */
@Singleton
class LinkDevices @Inject constructor(
    deviceRepository: BleDeviceRepository,
    syncController: DeviceSyncController,
    families: DeviceFamilies,
    scaleStore: XiaomiScaleStore,
    scaleListener: XiaomiScaleListener,
    @ApplicationScope appScope: CoroutineScope,
) {
    private val scope = appScope

    val devices: StateFlow<List<OpenLinkDevice>> = combine(
        deviceRepository.devicesFlow,
        syncController.state,
        families.connections,
        scaleStore.config,
        scaleListener.status,
    ) { registered, sync, connections, scale, scaleStatus ->
        linkDevices(registered, sync.syncingDeviceId, families, connections, scale, scaleStatus)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())
}

/** Pure, for the tests: what OpenLink shows of OpenWatch's devices. */
internal fun linkDevices(
    registered: List<BleSensorDevice>,
    syncingDeviceId: String?,
    families: DeviceFamilies,
    connections: Map<String, Boolean>,
    scale: XiaomiScaleConfig,
    scaleStatus: ScaleListenerStatus,
): List<OpenLinkDevice> {
    val watches = registered.mapNotNull { device ->
        val family = families.of(device) ?: return@mapNotNull null
        // The contract names its integrations; a family it has no name for stays off OpenLink until it does.
        val integration = OpenLinkIntegration.entries.firstOrNull { it.name == family.id } ?: return@mapNotNull null
        val features = family.features(device)
        OpenLinkDevice(
            id = device.id,
            displayName = device.displayName,
            kind = if (device.isBikeComputer) OpenLinkDeviceKind.BIKE_COMPUTER else OpenLinkDeviceKind.WATCH,
            integration = integration,
            connection = when (connections[device.id]) {
                true -> OpenLinkConnection.CONNECTED
                false -> OpenLinkConnection.DISCONNECTED
                null -> OpenLinkConnection.UNKNOWN
            },
            batteryPercent = device.batteryPercent,
            batteryUpdatedAtMillis = device.batteryUpdatedAt?.toEpochMilli(),
            lastSyncedAtMillis = device.lastSyncedAt?.toEpochMilli(),
            syncing = device.id == syncingDeviceId,
            supportsSync = DeviceFeature.SYNC in features,
            supportsRecording = true,
            liveSampleTypes = buildSet {
                if (DeviceFeature.LIVE_HEART_RATE in features) add(OpenLinkSampleType.HEART_RATE)
                if (DeviceFeature.LIVE_STEPS in features) add(OpenLinkSampleType.STEPS)
            },
        )
    }
    val scaleDevice = scale.address?.takeIf { scale.isSetUp }?.let { address ->
        OpenLinkDevice(
            id = SCALE_ID_PREFIX + address,
            displayName = scale.name ?: "S400",
            kind = OpenLinkDeviceKind.SCALE,
            integration = OpenLinkIntegration.XIAOMI,
            connection = if (scaleStatus == ScaleListenerStatus.LISTENING) OpenLinkConnection.CONNECTED else OpenLinkConnection.DISCONNECTED,
        )
    }
    return watches + listOfNotNull(scaleDevice)
}

internal const val SCALE_ID_PREFIX = "xiaomi-scale:"
