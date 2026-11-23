package tech.mmarca.openwatch.features.watches.generic

import android.app.Application
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.mmarca.openwatch.data.repository.testDeviceRepository
import tech.mmarca.openwatch.devices.core.DeviceFamilies
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.LiveReading
import tech.mmarca.openwatch.devices.core.LiveReadingKind
import tech.mmarca.openwatch.devices.core.measure.MeasurementOutcome
import tech.mmarca.openwatch.devices.core.measure.MeasurementSink
import tech.mmarca.openwatch.devices.core.measure.PushedMeasurement
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.domain.model.DeviceIntegration
import tech.mmarca.openwatch.navigation.DeviceDetail
import tech.mmarca.openwatch.navigation.routeHandle
import tech.mmarca.openwatch.util.MainDispatcherRule

/** The panel a family with none of its own gets: what its contracts say, and nothing named. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class GenericDevicePanelViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val registry = testDeviceRepository()
    private val device = registry.addDevice(
        "Strap", "AA:BB:CC:DD:EE:01", "Strap", emptySet(),
        kind = BleDeviceKind.SENSOR, integration = DeviceIntegration("SOMEVENDOR"),
    )
    private val readings = MutableSharedFlow<LiveReading>()
    private var streaming = false

    private val family = object : DeviceFamily {
        override val id = "SOMEVENDOR"
        override fun owns(device: BleSensorDevice) = device.integration?.storageName == id
        override fun features(device: BleSensorDevice) = setOf(DeviceFeature.LIVE_HEART_RATE)
        override fun liveReadings(device: BleSensorDevice): Flow<LiveReading> =
            readings.onStart { streaming = true }.onCompletion { streaming = false }
        override suspend fun forget(device: BleSensorDevice, deleteHistory: Boolean) = Unit
    }

    private val sink = object : MeasurementSink {
        val retried = mutableListOf<String?>()
        override val backlog: StateFlow<Map<String, MeasurementOutcome>> = MutableStateFlow(
            mapOf(device.id to MeasurementOutcome(pending = 2, withheld = setOf("android.permission.health.WRITE_HEART_RATE"))),
        )
        override suspend fun accept(device: BleSensorDevice, measurements: List<PushedMeasurement>) = MeasurementOutcome()
        override suspend fun retryPending(device: BleSensorDevice?): MeasurementOutcome {
            retried += device?.id
            return MeasurementOutcome()
        }
    }

    private fun viewModel() = GenericDevicePanelViewModel(
        routeHandle(DeviceDetail(device.id)), registry, DeviceFamilies(mapOf(family.id to family)), sink,
    )

    @Test
    fun `the family's heart rate streams while the screen collects it, and only then`() = runTest {
        val model = viewModel()
        assertFalse(streaming)

        val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.liveHeartRateBpm.collect {} }
        assertTrue(streaming)
        readings.emit(LiveReading(LiveReadingKind.STEPS, 1L, 900.0))
        assertNull(model.liveHeartRateBpm.value)
        readings.emit(LiveReading(LiveReadingKind.HEART_RATE, 2L, 71.6))
        assertEquals(72, model.liveHeartRateBpm.value)

        collecting.cancel()
    }

    @Test
    fun `what waits for Health Connect is shown, and a retry is the device's`() = runTest {
        val model = viewModel()

        assertEquals(2, model.backlog.value?.pending)
        model.retry()

        assertEquals(listOf(device.id), sink.retried)
    }
}
