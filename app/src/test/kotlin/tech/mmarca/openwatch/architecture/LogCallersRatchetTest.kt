package tech.mmarca.openwatch.architecture

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * The files that may write to Android's log, in the phone app, the watch app and the two
 * pure modules. What leaves the app is structure, not data: a log line holds a count, a
 * type or a reason, never a value, a name or an address. A file joins this list only after
 * its log lines are read with that in mind; the list only shrinks otherwise. The debug-only
 * loggers (`DeviceLog`, `GarminLog`, `WearOsLog`, `ScaleLog`) are where new logging belongs.
 */
class LogCallersRatchetTest {

    @Test
    fun `only the listed files write to Android's log`() {
        assertWithMessage(
            "a new file writes to android.util.Log. Log through a debug-only logger, or read its " +
                "lines for values, names and addresses and add it here.",
        )
            .that(callers() - Allowed)
            .isEmpty()
    }

    @Test
    fun `the list holds no file that stopped logging`() {
        assertWithMessage("remove these from the list").that(Allowed - callers()).isEmpty()
    }

    private fun callers(): Set<String> = Roots.flatMap { (module, root) ->
        File(root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file -> file.readText().let { UsesLog.containsMatchIn(it) } }
            .map { file -> "$module/" + file.relativeTo(File(root)).invariantSeparatorsPath }
            .toList()
    }.toSet()

    private companion object {
        val UsesLog = Regex("""^import android\.util\.Log$|android\.util\.Log\.""", RegexOption.MULTILINE)

        val Roots = mapOf(
            "app" to "src/main/kotlin/tech/mmarca/openwatch",
            "wear" to "../wear/src/main/kotlin/tech/mmarca/openwatch",
            "domain" to "../domain/src/main/kotlin/tech/mmarca/openwatch",
            "wearlink" to "../wearlink/src/main/kotlin/tech/mmarca/openwatch",
        )

        val Allowed = setOf(
            "app/AppStartupActors.kt",
            "app/core/presentation/ScreenError.kt",
            "app/data/repository/ActivityWriteRepository.kt",
            "app/data/repository/BleDeviceRepository.kt",
            "app/data/repository/DeviceStateStore.kt",
            "app/devices/core/DeviceLog.kt",
            "app/devices/core/pairing/BleWatchPairing.kt",
            "app/devices/core/pairing/CompanionDevicePairing.kt",
            "app/devices/core/pairing/OpenWatchCompanionDeviceService.kt",
            "app/devices/garmin/GarminLog.kt",
            "app/devices/garmin/GarminNotificationBridge.kt",
            "app/devices/notifications/Diagnostics.kt",
            "app/devices/notifications/OpenWatchNotificationListenerService.kt",
            "app/devices/wearos/BluetoothWearOsNodePort.kt",
            "app/devices/wearos/WearOsLog.kt",
            "app/devices/wearos/WearOsPhoneActivity.kt",
            "app/devices/xiaomi/ScaleListeningService.kt",
            "app/devices/xiaomi/ScaleLog.kt",
            "app/devices/xiaomi/ScaleScanRadio.kt",
            "app/devices/xiaomi/XiaomiScaleListener.kt",
            "app/healthconnect/ActivityRecordWriter.kt",
            "app/healthconnect/HealthConnectAvailabilityService.kt",
            "app/healthconnect/HealthConnectGateway.kt",
            "app/link/LinkLiveSessions.kt",
            "app/sensors/ble/BleSensorCoordinator.kt",
            "wear/wear/AscentRecorder.kt",
            "wear/wear/BedtimeMonitor.kt",
            "wear/wear/DebugProtocol.kt",
            "wear/wear/HeartRateRecorder.kt",
            "wear/wear/HrvWindowSource.kt",
            "wear/wear/LegacyStoreImport.kt",
            "wear/wear/LiveHeartRate.kt",
            "wear/wear/MetricTileService.kt",
            "wear/wear/NightHrvRecorder.kt",
            "wear/wear/PpgRawLogger.kt",
            "wear/wear/SkinTemperatureRecorder.kt",
            "wear/wear/SleepMinuteRecorder.kt",
            "wear/wear/Spo2Recorder.kt",
            "wear/wear/StepRecorder.kt",
            "wear/wear/WearAppService.kt",
            "wear/wear/WearLinkServerHost.kt",
            "wear/wear/WearPhoneRequests.kt",
            "wear/wear/WornMonitor.kt",
            "wear/wear/samsung/SamsungHealthPlatform.kt",
            "wear/wear/workout/WorkoutRecorder.kt",
        )
    }
}
