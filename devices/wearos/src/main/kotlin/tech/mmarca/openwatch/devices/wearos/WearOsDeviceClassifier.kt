package tech.mmarca.openwatch.devices.wearos

import tech.mmarca.openwatch.devices.core.DeviceClassification
import tech.mmarca.openwatch.devices.core.DeviceClassifier
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.wearlink.WearLinkProtocol

/**
 * Claims a device for WearOS when its name looks like a wrist smartwatch or
 * its SDP record lists the OpenWatch Wear OS app. The last one holds whatever
 * the watch is called. A wrist-watch Bluetooth class alone is not enough:
 * Garmin, Fitbit and Huawei watches report it too.
 * Classified as `(WEAROS, WATCH)`, off the Garmin sync path.
 */
class WearOsDeviceClassifier : DeviceClassifier {

    override fun classify(device: BleDiscoveredDevice): DeviceClassification? =
        if (
            WearOsDeviceNames.isSmartwatchName(device.name) ||
            OpenWatchAppUuid in device.classicServiceUuids
        ) {
            DeviceClassification(
                integration = DeviceIntegration.WEAROS,
                kind = BleDeviceKind.WATCH,
            )
        } else {
            null
        }
}

private val OpenWatchAppUuid = WearLinkProtocol.SERVICE_UUID.toString().lowercase()
