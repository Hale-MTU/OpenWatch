package tech.mmarca.openwatch.devices.wearos

import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/** A Wear OS watch running the OpenWatch watch app. The Wear OS package's own question; see [WearOsFamily]. */
val BleSensorDevice.isWearOs: Boolean
    get() = isWatch && integration == DeviceIntegration.WEAROS
