package tech.mmarca.openwatch.sensors.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.os.ParcelUuid
import android.os.Parcelable
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import tech.mmarca.openwatch.devices.core.DeviceClassification
import tech.mmarca.openwatch.devices.core.DeviceClassifier
import tech.mmarca.openwatch.devices.core.DeviceScanClassifier
import tech.mmarca.openwatch.devices.core.classifyDevice
import tech.mmarca.openwatch.devices.garmin.GarminDeviceClassifier
import tech.mmarca.openwatch.devices.garmin.GarminScanClassifier
import tech.mmarca.openwatch.devices.wearos.WearOsDeviceClassifier
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorCapability

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The BLE discovery scan the add-watch flow uses: what is advertising, what is
 * bonded, and which integration claims each. OpenVitals' coordinator also
 * streams live sensors into a recording; OpenWatch records nothing, so that
 * half stayed behind.
 */
@Singleton
class BleSensorCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val bleThread = HandlerThread("OpenWatchBle").apply { start() }
    private val bleHandler = Handler(bleThread.looper)
    private val bluetoothManager =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _discoveredDevices = MutableStateFlow<List<BleDiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<BleDiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var scanCallback: ScanCallback? = null
    private var sdpReceiver: BroadcastReceiver? = null
    private val scanResults = ConcurrentHashMap<String, BleDiscoveredDevice>()

    /** Per-integration classifiers: scan-time evidence, and the `(integration, kind)` verdict. */
    private val scanClassifiers: List<DeviceScanClassifier> = listOf(GarminScanClassifier())
    private val deviceClassifiers: List<DeviceClassifier> = listOf(
        GarminDeviceClassifier(),
        WearOsDeviceClassifier(),
    )

    fun startScan(showAllDevices: Boolean = false) {
        stopScan()
        scanResults.clear()
        publishScanResults()
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) return

        val filters = if (showAllDevices) {
            emptyList()
        } else {
            BleUuids.SCAN_SERVICE_UUIDS.map { serviceUuid ->
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(serviceUuid))
                    .build()
            }
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                addScanResult(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { addScanResult(it) }
            }
        }
        scanCallback = callback
        try {
            adapter.bluetoothLeScanner?.startScan(
                filters,
                ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build(),
                callback,
            )
        } catch (error: SecurityException) {
            // A revoked grant must not crash the add-device flow.
            Log.w(TAG, "BLE scan refused: ${error.message}")
            scanCallback = null
            return
        }
        val bonded = bondedDevices()
        bonded.forEach { device ->
            scanResults.putIfAbsent(
                device.address.uppercase(),
                BleDiscoveredDevice(
                    address = device.address.uppercase(),
                    name = device.name,
                    rssi = null,
                    suggestedCapabilities = emptySet(),
                    classicServiceUuids = device.uuids.toUuidStrings(),
                ),
            )
        }
        publishScanResults()
        refreshBondedServices(bonded)
    }

    /**
     * The bond's service list is cached from pairing, before any app on the
     * device listened. A fresh SDP query lets a classifier see the services
     * running now. Only unclaimed dual-mode bonds are paged: one a classifier
     * already claims (a Garmin, a known smartwatch name) gains nothing, and
     * audio/video, peripheral and imaging bonds never run the app.
     */
    @SuppressLint("MissingPermission")
    private fun refreshBondedServices(bonded: List<BluetoothDevice>) {
        val candidates = bonded.filter {
            it.type == BluetoothDevice.DEVICE_TYPE_DUAL &&
                it.bluetoothClass?.majorDeviceClass !in NeverRunsTheApp &&
                scanResults[it.address.uppercase()]
                    ?.let(::classifyDiscoveredDevice) == DeviceClassification.SENSOR
        }
        if (candidates.isEmpty()) return
        // A restarted scan registers afresh; the old receiver must not leak.
        sdpReceiver?.let { old -> runCatching { context.unregisterReceiver(old) } }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val device = intent.parcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                val uuids = intent.parcelableArray(BluetoothDevice.EXTRA_UUID)
                    ?.filterIsInstance<ParcelUuid>()
                    ?.toTypedArray()
                    .toUuidStrings()
                if (uuids.isEmpty()) return
                // Atomic per key: an advertisement for the same device lands on the scan thread.
                scanResults.computeIfPresent(device.address.uppercase()) { _, existing ->
                    existing.copy(classicServiceUuids = existing.classicServiceUuids + uuids)
                } ?: return
                publishScanResults()
            }
        }
        sdpReceiver = receiver
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(BluetoothDevice.ACTION_UUID),
            ContextCompat.RECEIVER_EXPORTED,
        )
        candidates.forEach { device ->
            try {
                device.fetchUuidsWithSdp()
            } catch (error: SecurityException) {
                Log.w(TAG, "SDP refresh refused: ${error.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanCallback?.let { callback ->
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(callback)
        }
        scanCallback = null
        sdpReceiver?.let { receiver -> runCatching { context.unregisterReceiver(receiver) } }
        sdpReceiver = null
    }

    @SuppressLint("MissingPermission")
    suspend fun discoverCapabilities(address: String): Set<BleSensorCapability> =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val adapter = bluetoothAdapter
                if (adapter == null || !adapter.isEnabled) {
                    continuation.resume(emptySet())
                    return@suspendCancellableCoroutine
                }
                val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull()
                if (device == null) {
                    continuation.resume(emptySet())
                    return@suspendCancellableCoroutine
                }

                val discovered = mutableSetOf<BleSensorCapability>()
                var gatt: BluetoothGatt? = null
                var finished = false
                var timeoutRunnable: Runnable? = null

                fun complete(result: Set<BleSensorCapability>) {
                    if (finished || !continuation.isActive) return
                    finished = true
                    timeoutRunnable?.let { bleHandler.removeCallbacks(it) }
                    runCatching { gatt?.close() }
                    gatt = null
                    continuation.resume(result)
                }

                timeoutRunnable = Runnable { complete(discovered.toSet()) }

                continuation.invokeOnCancellation {
                    finished = true
                    timeoutRunnable?.let { bleHandler.removeCallbacks(it) }
                    runCatching { gatt?.disconnect() }
                    runCatching { gatt?.close() }
                    gatt = null
                }

                gatt = device.connectGatt(
                    context,
                    false,
                    object : BluetoothGattCallback() {
                        @SuppressLint("MissingPermission")
                        override fun onConnectionStateChange(
                            gatt: BluetoothGatt,
                            status: Int,
                            newState: Int,
                        ) {
                            when (newState) {
                                BluetoothProfile.STATE_CONNECTED -> gatt.discoverServices()
                                BluetoothProfile.STATE_DISCONNECTED -> complete(discovered.toSet())
                            }
                        }

                        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                gatt.services.forEach { service ->
                                    discovered.addAll(BleUuids.capabilitiesForService(service.uuid))
                                }
                            }
                            runCatching { gatt.disconnect() }
                        }
                    },
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M,
                    bleHandler,
                )
                bleHandler.postDelayed(timeoutRunnable!!, CAPABILITY_DISCOVERY_TIMEOUT_MS)
            }
        }

    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<BluetoothDevice> =
        try {
            bluetoothAdapter?.bondedDevices?.filter {
                it.type == BluetoothDevice.DEVICE_TYPE_LE ||
                    it.type == BluetoothDevice.DEVICE_TYPE_DUAL
            }.orEmpty()
        } catch (error: SecurityException) {
            Log.w(TAG, "Bonded devices refused: ${error.message}")
            emptyList()
        }

    @SuppressLint("MissingPermission")
    private fun addScanResult(result: ScanResult) {
        val device = result.device ?: return
        val address = device.address.uppercase()
        val advertisedUuids = result.scanRecord?.serviceUuids?.map { it.uuid }.orEmpty()
        val serviceCapabilities = advertisedUuids
            .flatMap { BleUuids.capabilitiesForService(it) }
            .toSet()
        // Which integration claims this advertisement as a sync watch. The
        // scanner carries no protocol knowledge of its own.
        val advertisedUuidStrings = advertisedUuids.map { it.toString() }
        val advertisesSync =
            scanClassifiers.any { it.advertisesSyncService(advertisedUuidStrings) }
        // Atomic per key: the SDP receiver updates the same entry on the main thread.
        scanResults.compute(address) { _, existing -> BleDiscoveredDevice(
            address = address,
            name = device.name ?: existing?.name,
            rssi = result.rssi,
            suggestedCapabilities = (existing?.suggestedCapabilities.orEmpty() + serviceCapabilities).toSet(),
            // Sticky across advertisements: one sighting of the member service settles it.
            advertisesSyncService = advertisesSync || (existing?.advertisesSyncService ?: false),
            // Bond evidence comes from the bonded list and SDP, never an advertisement.
            classicServiceUuids = existing?.classicServiceUuids.orEmpty(),
        ) }
        publishScanResults()
    }

    /**
     * Maps a discovered device to how it registers: Garmin watch, Edge,
     * WearOS watch, or a plain sensor. Garmin's verdict beats the name match.
     */
    fun classifyDiscoveredDevice(device: BleDiscoveredDevice): DeviceClassification =
        classifyDevice(device, deviceClassifiers)

    private fun publishScanResults() {
        _discoveredDevices.value = scanResults.values.sortedWith(
            compareByDescending<BleDiscoveredDevice> { it.rssi ?: Int.MIN_VALUE }
                .thenBy { it.displayLabel() },
        )
    }


    private fun BleDiscoveredDevice.displayLabel(): String =
        name?.ifBlank { address } ?: address


    companion object {
        private const val TAG = "BleSensorCoordinator"
        private const val CAPABILITY_DISCOVERY_TIMEOUT_MS = 8_000L
    }
}
/** Major classes that never run the OpenWatch Wear OS app. */
private val NeverRunsTheApp = setOf(
    BluetoothClass.Device.Major.AUDIO_VIDEO,
    BluetoothClass.Device.Major.PERIPHERAL,
    BluetoothClass.Device.Major.IMAGING,
)

private fun Array<ParcelUuid>?.toUuidStrings(): Set<String> =
    this?.map { it.uuid.toString().lowercase() }?.toSet().orEmpty()

@Suppress("DEPRECATION")
private inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key)
    }

@Suppress("DEPRECATION")
private fun Intent.parcelableArray(key: String): Array<out Parcelable>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayExtra(key, ParcelUuid::class.java)
    } else {
        getParcelableArrayExtra(key)
    }
