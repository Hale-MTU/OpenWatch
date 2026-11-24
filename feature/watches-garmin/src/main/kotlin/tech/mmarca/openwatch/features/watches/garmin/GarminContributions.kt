package tech.mmarca.openwatch.features.watches.garmin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap
import dagger.multibindings.StringKey
import javax.inject.Inject
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.devices.core.pairing.WatchBondResult
import tech.mmarca.openwatch.devices.core.sync.DeviceSyncUiState
import tech.mmarca.openwatch.devices.garmin.GarminOnboardOutcome
import tech.mmarca.openwatch.devices.garmin.GarminOnboardStep
import tech.mmarca.openwatch.devices.garmin.GarminTransportVariant
import tech.mmarca.openwatch.devices.garmin.OnboardGarminWatchUseCase
import tech.mmarca.openwatch.domain.model.BleDeviceKind
import tech.mmarca.openwatch.domain.model.BleDiscoveredDevice
import tech.mmarca.openwatch.domain.model.BleSensorDevice
import tech.mmarca.openwatch.features.device.DevicePanelActions
import tech.mmarca.openwatch.features.device.DevicePanelContribution
import tech.mmarca.openwatch.features.device.WatchOnboardResult
import tech.mmarca.openwatch.features.device.WatchOnboarding
import tech.mmarca.openwatch.features.watches.WatchDevicePanel
import tech.mmarca.openwatch.features.watches.WatchOnboardNotice
import tech.mmarca.openwatch.navigation.WatchAlarms
import tech.mmarca.openwatch.navigation.WatchData
import tech.mmarca.openwatch.navigation.WatchNotifications
import tech.mmarca.openwatch.navigation.WatchSendPoint
import tech.mmarca.openwatch.navigation.WatchSettings
import tech.mmarca.openwatch.ui.theme.Spacing

/** The Garmin watches' part of the device screen: their panel, which opens the Garmin screens. */
class GarminDevicePanel @Inject constructor() : DevicePanelContribution {
    @Composable
    override fun Panel(
        device: BleSensorDevice,
        sync: DeviceSyncUiState,
        actions: DevicePanelActions,
        content: @Composable (WatchDevicePanel) -> Unit,
    ) {
        GarminWatchPanelRoute(
            device = device,
            sync = sync,
            onSync = actions.onSync,
            onAllowSyncGrants = actions.onAllowSyncGrants,
            onOpenData = { deviceId -> actions.host.navigate(WatchData(deviceId)) },
            onOpenNotifications = { deviceId -> actions.host.navigate(WatchNotifications(deviceId)) },
            onOpenWatchSettings = { deviceId, screenId -> actions.host.navigate(WatchSettings(deviceId, screenId)) },
            onOpenSendPoint = { deviceId -> actions.host.navigate(WatchSendPoint(deviceId = deviceId)) },
            onOpenAlarms = { deviceId -> actions.host.navigate(WatchAlarms(deviceId)) },
            content = content,
        )
    }
}

/** A Garmin watch's pairing for the add-a-watch flow: the bond, the companion association and the GFDI probe. */
class GarminWatchOnboarding @Inject constructor(
    private val onboardGarminWatch: OnboardGarminWatchUseCase,
) : WatchOnboarding {

    override suspend fun onboard(
        device: BleDiscoveredDevice,
        displayName: String,
        kind: BleDeviceKind,
        onStep: (Int) -> Unit,
    ): WatchOnboardResult = when (
        val outcome = onboardGarminWatch(device, displayName = displayName, kind = kind, onStep = { onStep(it.ordinal) })
    ) {
        is GarminOnboardOutcome.Failed -> WatchOnboardResult.Failed(
            ScreenError.Text(
                when (outcome.reason) {
                    WatchBondResult.UNREACHABLE -> R.string.settings_watch_pair_unreachable
                    else -> R.string.settings_watch_pair_not_completed
                },
            ),
        )

        is GarminOnboardOutcome.Succeeded -> WatchOnboardResult.Paired(
            notice = when {
                // An unsupported transport outranks a missing association.
                outcome.transport.variant == GarminTransportVariant.UNKNOWN ||
                    outcome.transport.variant == GarminTransportVariant.UNREACHABLE ->
                    WatchOnboardNotice.UNSUPPORTED_TRANSPORT

                !outcome.associated -> WatchOnboardNotice.NO_COMPANION
                else -> null
            },
        )
    }

    @Composable
    override fun Steps(step: Int?) = WatchPairSteps(step = step?.let(GarminOnboardStep.entries::getOrNull))
}

/** The Garmin watches' contributions to the generic screens, by the family's persisted name. */
@Module
@InstallIn(SingletonComponent::class)
internal interface GarminFeatureModule {
    @Binds
    @IntoMap
    @StringKey("GARMIN")
    fun bindPanel(panel: GarminDevicePanel): DevicePanelContribution

    @Binds
    @IntoMap
    @StringKey("GARMIN")
    fun bindOnboarding(onboarding: GarminWatchOnboarding): WatchOnboarding
}

/** The platform steps of onboarding, as a checklist. [step] is null outside pairing. */
@Composable
private fun WatchPairSteps(step: GarminOnboardStep?) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Text(
            text = stringResource(R.string.settings_watch_pair_title),
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = stringResource(R.string.settings_watch_pair_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.padding(top = Spacing.xs))
        WatchPairStepRow(
            label = stringResource(R.string.settings_watch_step_bonding),
            active = step == GarminOnboardStep.BONDING,
            done = step == GarminOnboardStep.ASSOCIATING || step == GarminOnboardStep.PROBING,
        )
        WatchPairStepRow(
            label = stringResource(R.string.settings_watch_step_associating),
            active = step == GarminOnboardStep.ASSOCIATING,
            done = step == GarminOnboardStep.PROBING,
        )
        Text(
            text = stringResource(R.string.settings_watch_step_associating_hint),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = Spacing.xxxl),
        )
        WatchPairStepRow(
            label = stringResource(R.string.settings_watch_step_probing),
            active = step == GarminOnboardStep.PROBING,
            done = false,
        )
    }
}

@Composable
private fun WatchPairStepRow(
    label: String,
    active: Boolean,
    done: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        when {
            active -> CircularProgressIndicator(
                modifier = Modifier.size(Spacing.lg),
                strokeWidth = WatchProgressStroke,
            )

            done -> Icon(
                imageVector = Icons.Outlined.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(WatchPermissionIconSize),
                tint = MaterialTheme.colorScheme.primary,
            )

            else -> Icon(
                imageVector = Icons.Outlined.RadioButtonUnchecked,
                contentDescription = null,
                modifier = Modifier.size(WatchPermissionIconSize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (active) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

/** The small in-row progress spinner's stroke. */
private val WatchProgressStroke = 2.dp

/** The step icons' size, as the watch list's permission rows have. */
private val WatchPermissionIconSize = 18.dp
