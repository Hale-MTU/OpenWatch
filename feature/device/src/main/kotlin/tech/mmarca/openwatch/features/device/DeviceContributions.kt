package tech.mmarca.openwatch.features.device

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncUiState
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.features.watches.WatchDevicePanel
import tech.mmarca.openwatch.features.watches.WatchOnboardNotice

/*
 * How a device family's own screens plug into the generic ones, so the device route, the device
 * screen and the add-a-watch flow name no family. A family's feature module binds what it brings
 * into these maps under its persisted name (the devices' `integration`, `DeviceFamily.id`), as its
 * device module binds the family itself. A family that binds none gets the generic device screen
 * and panel; a watch family with no onboarding cannot be paired through the add-a-watch flow.
 */

/** What a family's part of a device destination may ask of the app. */
@Immutable
class DeviceScreenHost(
    /** Opens one of the family's own destinations, a route its module declares and the app's graph holds. */
    val navigate: (route: Any) -> Unit,
    /** The body profile, which a family's estimates read. */
    val onOpenBodyProfile: () -> Unit,
    /** The device was removed: back to where it was opened from. */
    val onRemoved: () -> Unit,
)

/** A family that draws the whole destination of its devices, as the scale does. */
interface DeviceScreenContribution {
    @Composable
    fun Screen(host: DeviceScreenHost)
}

/** What the device screen hands a family's panel: its sync, and the way to the grants a sync left waiting. */
@Immutable
class DevicePanelActions(
    val onSync: () -> Unit,
    val onAllowSyncGrants: (Set<String>) -> Unit,
    val host: DeviceScreenHost,
)

/**
 * A family's panel in the device screen, as each watch has. It owns its state holder and its
 * launchers, and draws `content` with the [WatchDevicePanel] that fills the shared cards' gaps.
 */
interface DevicePanelContribution {
    @Composable
    fun Panel(
        device: BleSensorDevice,
        sync: DeviceSyncUiState,
        actions: DevicePanelActions,
        content: @Composable (WatchDevicePanel) -> Unit,
    )
}

/**
 * A watch family's own pairing, which the add-a-watch flow runs once the user picks a device
 * the family claims: bonding, the companion association, whatever the family needs.
 */
interface WatchOnboarding {
    /**
     * Pairs and registers [device] under [displayName] as [kind]. [onStep] reports the step
     * [Steps] shows, by its index; a pairing with no steps never calls it.
     */
    suspend fun onboard(
        device: BleDiscoveredDevice,
        displayName: String,
        kind: BleDeviceKind,
        onStep: (Int) -> Unit,
    ): WatchOnboardResult

    /** The checklist the add sheet shows while the pairing runs; [step] is null before the first. */
    @Composable
    fun Steps(step: Int?) = Unit
}

/** How a family's pairing ended, as the add-a-watch flow tells it. */
sealed interface WatchOnboardResult {
    /** Registered; [notice] is what the list says once the sheet closes, if anything. */
    data class Paired(val notice: WatchOnboardNotice?) : WatchOnboardResult

    /** Nothing was added, for [reason]; the sheet stays open to try again. */
    data class Failed(val reason: ScreenError) : WatchOnboardResult

    /** Nothing was added: the family needs the companion association, and it was not given. The sheet stays. */
    data object CompanionRequired : WatchOnboardResult
}

/** The contribution maps, which are empty until a family's feature module binds into them. */
@Module
@InstallIn(SingletonComponent::class)
internal interface DeviceContributionsModule {
    @Multibinds
    fun screens(): Map<String, DeviceScreenContribution>

    @Multibinds
    fun panels(): Map<String, DevicePanelContribution>

    @Multibinds
    fun onboardings(): Map<String, WatchOnboarding>
}
