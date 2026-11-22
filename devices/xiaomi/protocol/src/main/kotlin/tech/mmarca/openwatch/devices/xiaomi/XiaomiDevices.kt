package tech.mmarca.openwatch.devices.xiaomi

import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/** A Xiaomi S400 scale. The Xiaomi package's own question; see [XiaomiScaleFamily]. */
public val BleSensorDevice.isXiaomiScale: Boolean
    get() = kind == BleDeviceKind.SCALE && integration == DeviceIntegration.XIAOMI
