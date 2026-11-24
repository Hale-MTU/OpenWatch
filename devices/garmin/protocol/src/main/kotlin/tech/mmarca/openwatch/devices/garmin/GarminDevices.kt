package tech.mmarca.openwatch.devices.garmin

import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration

/**
 * Driven over GFDI: a Garmin watch or bike computer, or a watch stored before the integration
 * was recorded, which was always a Garmin. Named, never "not Wear OS": a family added later must
 * not be synced as a Garmin. The Garmin package's own question; the rest of the app asks the
 * device's family ([GarminFamily]).
 */
public val BleSensorDevice.isGarmin: Boolean
    get() = (kind == BleDeviceKind.WATCH || kind == BleDeviceKind.BIKE_COMPUTER) &&
        (integration == DeviceIntegration.GARMIN || integration == null)

/** A Garmin watch, not a bike computer. */
public val BleSensorDevice.isGarminWatch: Boolean
    get() = isGarmin && isWatch
