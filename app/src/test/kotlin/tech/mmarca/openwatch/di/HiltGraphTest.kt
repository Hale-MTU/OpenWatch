package tech.mmarca.openwatch.di

import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.HiltTestApplication
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import tech.mmarca.openwatch.AppStartupActors
import tech.mmarca.openwatch.devices.core.DeviceClassifier
import tech.mmarca.openwatch.devices.core.DeviceFamily
import tech.mmarca.openwatch.devices.core.measure.MeasurementSink
import tech.mmarca.openwatch.devices.core.pairing.CompanionPresenceObserver
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncPort
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncController
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway

/**
 * Builds the app's real Hilt graph on the JVM.
 *
 * Dagger already refuses a binding that is missing at compile time. What it cannot see is a
 * multibinding that is quietly short of one integration (an empty set is valid), or a
 * singleton whose construction throws; both surface here instead of on a phone.
 */
@HiltAndroidTest
@RunWith(RobolectricTestRunner::class)
@Config(application = HiltTestApplication::class, sdk = [34])
class HiltGraphTest {

    @get:Rule
    val hilt = HiltAndroidRule(this)

    @Inject lateinit var syncPorts: Set<@JvmSuppressWildcards DeviceSyncPort>
    @Inject lateinit var presenceObservers: Set<@JvmSuppressWildcards CompanionPresenceObserver>
    @Inject lateinit var syncController: DeviceSyncController
    @Inject lateinit var deviceClassifiers: List<@JvmSuppressWildcards DeviceClassifier>
    @Inject lateinit var families: Map<String, @JvmSuppressWildcards DeviceFamily>
    @Inject lateinit var healthConnect: HealthConnectGateway
    @Inject lateinit var startupActors: AppStartupActors
    @Inject lateinit var measurementSink: MeasurementSink

    @Before
    fun inject() = hilt.inject()

    @Test
    fun `every integration contributes its sync port and its presence observer`() {
        // Garmin and Wear OS sync; the scale and the Garmin bridge watch for presence.
        assertThat(syncPorts.map { it::class.simpleName })
            .containsExactly("GarminWatchSyncService", "WearOsWatchSyncService")
        assertThat(presenceObservers.map { it::class.simpleName })
            .containsExactly("XiaomiScaleListener", "GarminNotificationBridge")
        // Each family by its persisted name, whether the app or its own module binds it.
        assertThat(families.mapValues { it.value::class.simpleName })
            .containsExactly(
                "GARMIN", "GarminFamily",
                "WEAROS", "WearOsFamily",
                "XIAOMI", "XiaomiScaleFamily",
                "BLE_STANDARD", "StandardDeviceFamily",
            )
        // Order is the rule here: the first classifier that claims a device wins.
        assertThat(deviceClassifiers.map { it::class.simpleName })
            .containsExactly("GarminDeviceClassifier", "WearOsDeviceClassifier").inOrder()
    }

    @Test
    fun `the singletons the app starts with can be built`() {
        assertThat(syncController.state.value.isSyncing).isFalse()
        // Every integration's actors and the app's own, contributed by their modules.
        assertThat(startupActors.names).containsExactly(
            "garmin music",
            "garmin navigation",
            "garmin notifications",
            "garmin pruning",
            "openlink publisher",
            "pending measurements",
            "phone activity",
            "scale listener",
            "sync schedules",
        ).inOrder()
        assertThat(healthConnect.declaredPermissions).isNotEmpty()
        assertThat(measurementSink.backlog.value).isEmpty()
    }
}
