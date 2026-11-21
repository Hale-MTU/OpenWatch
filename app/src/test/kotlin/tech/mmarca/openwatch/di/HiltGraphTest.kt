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
import tech.mmarca.openwatch.devices.core.pairing.CompanionPresenceObserver
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncPort
import tech.mmarca.openwatch.features.watches.DeviceSyncController
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
    @Inject lateinit var healthConnect: HealthConnectGateway
    @Inject lateinit var startupActors: AppStartupActors

    @Before
    fun inject() = hilt.inject()

    @Test
    fun `every integration contributes its sync port and its presence observer`() {
        // Garmin and Wear OS sync; the scale and the Garmin bridge watch for presence.
        assertThat(syncPorts.map { it::class.simpleName })
            .containsExactly("GarminWatchSyncService", "WearOsWatchSyncService")
        assertThat(presenceObservers.map { it::class.simpleName })
            .containsExactly("XiaomiScaleListener", "GarminNotificationBridge")
    }

    @Test
    fun `the singletons the app starts with can be built`() {
        assertThat(syncController.state.value.isSyncing).isFalse()
        assertThat(startupActors).isNotNull()
        assertThat(healthConnect.declaredPermissions).isNotEmpty()
    }
}
