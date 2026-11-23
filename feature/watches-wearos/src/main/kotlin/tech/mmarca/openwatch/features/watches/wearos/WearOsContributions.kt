package tech.mmarca.openwatch.features.watches.wearos

import androidx.compose.runtime.Composable
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Inject
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncUiState
import tech.mmarca.openwatch.devices.wearos.OnboardWearOsWatchUseCase
import tech.mmarca.openwatch.devices.wearos.WearOsOnboardOutcome
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.features.device.DevicePanelActions
import tech.mmarca.openwatch.features.device.DevicePanelContribution
import tech.mmarca.openwatch.features.device.WatchOnboardResult
import tech.mmarca.openwatch.features.device.WatchOnboarding
import tech.mmarca.openwatch.features.watches.WatchDevicePanel

/** The Wear OS watch's part of the device screen: its panel. */
class WearOsDevicePanel @Inject constructor() : DevicePanelContribution {
    @Composable
    override fun Panel(
        device: BleSensorDevice,
        sync: DeviceSyncUiState,
        actions: DevicePanelActions,
        content: @Composable (WatchDevicePanel) -> Unit,
    ) {
        WearOsWatchPanelRoute(
            device = device,
            sync = sync,
            onSync = actions.onSync,
            onAllowSyncGrants = actions.onAllowSyncGrants,
            content = content,
        )
    }
}

/**
 * A Wear OS watch's pairing for the add-a-watch flow: no bond and no probe, only the companion
 * association, which it needs. It shows no steps.
 */
class WearOsWatchOnboarding @Inject constructor(
    private val onboardWearOsWatch: OnboardWearOsWatchUseCase,
) : WatchOnboarding {

    override suspend fun onboard(
        device: BleDiscoveredDevice,
        displayName: String,
        kind: BleDeviceKind,
        onStep: (Int) -> Unit,
    ): WatchOnboardResult = when (onboardWearOsWatch(device, displayName = displayName)) {
        is WearOsOnboardOutcome.Succeeded -> WatchOnboardResult.Paired(notice = null)
        WearOsOnboardOutcome.NotAssociated -> WatchOnboardResult.CompanionRequired
    }
}

/** The Wear OS watch's contributions to the generic screens, by the family's persisted name. */
@Module
@InstallIn(SingletonComponent::class)
internal interface WearOsFeatureModule {
    @Binds
    @IntoMap
    @StringKey("WEAROS")
    fun bindPanel(panel: WearOsDevicePanel): DevicePanelContribution

    @Binds
    @IntoMap
    @StringKey("WEAROS")
    fun bindOnboarding(onboarding: WearOsWatchOnboarding): WatchOnboarding
}
