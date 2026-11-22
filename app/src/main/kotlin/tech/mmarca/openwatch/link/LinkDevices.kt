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
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncController
import tech.mmarca.openwatch.core.performance.ApplicationScope

/**
 * The paired devices as OpenLink shows them: the registered devices a family owns, with what
 * the family says they can do and whether their link is up, and the sync state. Each goes by
 * its registry id, which says nothing about the device. Kept as a StateFlow so the binder
 * thread reads it from memory.
 */
@Singleton
class LinkDevices @Inject constructor(
    deviceRepository: BleDeviceRepository,
    syncController: DeviceSyncController,
    families: DeviceFamilies,
    @ApplicationScope appScope: CoroutineScope,
) {
    private val scope = appScope

    val devices: StateFlow<List<OpenLinkDevice>> = combine(
        deviceRepository.devicesFlow,
        syncController.state,
        families.connections,
    ) { registered, sync, connections ->
        linkDevices(registered, sync.syncingDeviceIds, families, connections)
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptyList())
}

/** Pure, for the tests: what OpenLink shows of OpenWatch's devices. */
internal fun linkDevices(
    registered: List<BleSensorDevice>,
    syncingDeviceIds: Set<String>,
    families: DeviceFamilies,
    connections: Map<String, Boolean>,
): List<OpenLinkDevice> =
    registered.mapNotNull { device ->
        val family = families.of(device) ?: return@mapNotNull null
        // The contract names its integrations; a family it has no name for stays off OpenLink until it does.
        val integration = OpenLinkIntegration.entries.firstOrNull { it.name == family.id } ?: return@mapNotNull null
        val features = family.features(device)
        OpenLinkDevice(
            id = device.id,
            displayName = device.displayName,
            kind = when (device.kind) {
                BleDeviceKind.BIKE_COMPUTER -> OpenLinkDeviceKind.BIKE_COMPUTER
                BleDeviceKind.SCALE -> OpenLinkDeviceKind.SCALE
                BleDeviceKind.WATCH, BleDeviceKind.SENSOR -> OpenLinkDeviceKind.WATCH
            },
            integration = integration,
            connection = when (connections[device.id]) {
                true -> OpenLinkConnection.CONNECTED
                false -> OpenLinkConnection.DISCONNECTED
                null -> OpenLinkConnection.UNKNOWN
            },
            batteryPercent = device.batteryPercent,
            batteryUpdatedAtMillis = device.batteryUpdatedAt?.toEpochMilli(),
            lastSyncedAtMillis = device.lastSyncedAt?.toEpochMilli(),
            syncing = device.id in syncingDeviceIds,
            supportsSync = DeviceFeature.SYNC in features,
            // A recording reads a wrist or a bike; a scale has neither.
            supportsRecording = device.kind != BleDeviceKind.SCALE,
            liveSampleTypes = buildSet {
                if (DeviceFeature.LIVE_HEART_RATE in features) add(OpenLinkSampleType.HEART_RATE)
                if (DeviceFeature.LIVE_STEPS in features) add(OpenLinkSampleType.STEPS)
            },
        )
    }
