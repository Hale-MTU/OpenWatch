package tech.mmarca.openwatch.domain.model

import java.time.Instant

/**
 * What a registered Bluetooth device is. A [WATCH] runs the OpenWatch Wear OS
 * app; a [SENSOR] is anything else a scan found, which the add-watch flow
 * does not list.
 */
enum class BleDeviceKind(
    /** Persisted form, so a rename cannot orphan stored devices. */
    val storageName: String,
) {
    SENSOR("SENSOR"),
    WATCH("WATCH"),
    ;

    companion object {
        fun fromStorage(value: String): BleDeviceKind? =
            entries.firstOrNull { it.storageName == value }
    }
}

/** Which integration owns a [BleDeviceKind.WATCH]. Null for a sensor. */
enum class DeviceIntegration(val storageName: String) {
    WEAROS("WEAROS"),
    ;

    companion object {
        fun fromStorage(value: String): DeviceIntegration? =
            entries.firstOrNull { it.storageName == value }
    }
}

enum class BleSensorCapability {
    HEART_RATE,
    CYCLING_CADENCE,
    CYCLING_POWER,
    CYCLING_SPEED_DISTANCE,
    RUNNING_SPEED_CADENCE,
}

enum class BleConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    RECONNECTING,
}

data class BleSensorDevice(
    val id: String,
    val displayName: String,
    val address: String,
    val bluetoothName: String?,
    val capabilities: Set<BleSensorCapability>,
    val enabled: Boolean,
    val wheelCircumferenceMm: Int?,
    val batteryPercent: Int? = null,
    val batteryUpdatedAt: Instant? = null,
    val addedAt: Instant,
    /** Defaulted so devices stored before this field existed keep their meaning. */
    val kind: BleDeviceKind = BleDeviceKind.SENSOR,
    /** The owning integration for a watch. */
    val integration: DeviceIntegration? = null,
    /** When recorded files were last pulled. Null for a sensor or a never-synced watch. */
    val lastSyncedAt: Instant? = null,
) {
    val isWatch: Boolean
        get() = kind == BleDeviceKind.WATCH

    /** A WearOS watch: live heart rate over BLE, recorded data via Health Connect. */
    val isWearosWatch: Boolean
        get() = isWatch && integration == DeviceIntegration.WEAROS

    fun normalized(): BleSensorDevice =
        copy(
            displayName = displayName.trim().ifBlank { bluetoothName.orEmpty().ifBlank { address } },
            wheelCircumferenceMm = wheelCircumferenceMm?.coerceIn(
                MinWheelCircumferenceMm,
                MaxWheelCircumferenceMm,
            ),
            batteryPercent = batteryPercent?.coerceIn(0, 100),
        )

    companion object {
        const val DefaultWheelCircumferenceMm = 2_100

        /** Below a 12-inch kids' wheel (about 940 mm), so users can calibrate small wheels. */
        const val MinWheelCircumferenceMm = 500
        const val MaxWheelCircumferenceMm = 3_000
    }
}

data class BleDeviceConnectionStatus(
    val deviceId: String,
    val displayName: String,
    val address: String,
    val status: BleConnectionStatus,
    val capabilities: Set<BleSensorCapability>,
    val batteryPercent: Int? = null,
)

data class BleRecordingMetrics(
    val heartRateBpm: Long? = null,
    val cyclingCadenceRpm: Long? = null,
    val powerWatts: Double? = null,
    val cyclingSpeedMetersPerSecond: Double? = null,
    /** Wheel distance since the speed sensor connected. Starts again at 0 after a reconnect. */
    val cyclingDistanceMeters: Double? = null,
    val runningSpeedMetersPerSecond: Double? = null,
    val runningCadenceRpm: Long? = null,
    val heartRateNoSignal: Boolean = false,
    val deviceStatuses: List<BleDeviceConnectionStatus> = emptyList(),
)

data class BleDiscoveredDevice(
    val address: String,
    val name: String?,
    val rssi: Int?,
    val suggestedCapabilities: Set<BleSensorCapability>,
    /**
     * Bonded devices only: the Classic service UUIDs the bond's SDP record
     * lists, lowercase. An app listening on the device shows up here.
     */
    val classicServiceUuids: Set<String> = emptySet(),
)
