package tech.mmarca.openwatch.features.watches.generic

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.R
import tech.mmarca.openwatch.data.repository.BleDeviceRepository
import tech.mmarca.openwatch.devices.core.DeviceFamilies
import tech.mmarca.openwatch.devices.core.DeviceFeature
import tech.mmarca.openwatch.devices.core.LiveReadingKind
import tech.mmarca.openwatch.devices.core.measure.MeasurementOutcome
import tech.mmarca.openwatch.devices.core.measure.MeasurementSink
import tech.mmarca.openwatch.features.watches.WatchDevicePanel
import tech.mmarca.openwatch.features.watches.toScreenError
import tech.mmarca.openwatch.core.presentation.resolve
import tech.mmarca.openwatch.navigation.DeviceDetail
import tech.mmarca.openwatch.ui.components.InlineGrantPrompt
import tech.mmarca.openwatch.ui.components.OpenWatchCard
import tech.mmarca.openwatch.ui.components.SectionHeader
import tech.mmarca.openwatch.ui.components.rememberHealthConnectPermissionLauncher
import tech.mmarca.openwatch.ui.theme.OpenWatchTheme
import tech.mmarca.openwatch.ui.theme.Spacing

/**
 * The panel of a device whose family brings none of its own: what every family can say through
 * its contracts. Its live heart rate while the screen is open, when the family streams one
 * ([DeviceFeature.LIVE_HEART_RATE]), and what it pushed that Health Connect has not taken yet,
 * with the grant that would let it ([MeasurementSink.backlog]).
 */
@HiltViewModel
class GenericDevicePanelViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val deviceRepository: BleDeviceRepository,
    private val families: DeviceFamilies,
    private val sink: MeasurementSink,
) : ViewModel() {

    private val deviceId: String = savedStateHandle.toRoute<DeviceDetail>().deviceId

    /**
     * The device's heart rate as it is measured, null while none arrives. Streams only while the
     * screen collects it, so the family holds its link only while someone looks.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val liveHeartRateBpm: StateFlow<Int?> = deviceRepository.devicesFlow
        .map { devices -> devices.firstOrNull { it.id == deviceId } }
        .distinctUntilChanged { old, new -> old?.address == new?.address && old?.enabled == new?.enabled }
        .flatMapLatest { device -> device?.takeIf { it.enabled }?.let(::heartRate) ?: emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(LIVE_STOP_TIMEOUT_MS), null)

    /** What the device pushed that is still waiting for Health Connect; null when nothing is. */
    val backlog: StateFlow<MeasurementOutcome?> = sink.backlog
        .map { it[deviceId] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(LIVE_STOP_TIMEOUT_MS), sink.backlog.value[deviceId])

    /** Tries the waiting measurements again: after a grant, or on a tap. */
    fun retry() {
        val device = deviceRepository.devices.firstOrNull { it.id == deviceId } ?: return
        viewModelScope.launch { sink.retryPending(device) }
    }

    private fun heartRate(device: tech.mmarca.openwatch.domain.model.BleSensorDevice): Flow<Int?> {
        val family = families.of(device)?.takeIf { DeviceFeature.LIVE_HEART_RATE in it.features(device) } ?: return emptyFlow()
        return family.liveReadings(device)
            .filter { it.kind == LiveReadingKind.HEART_RATE }
            .map<_, Int?> { it.value.roundToInt() }
            // A stream that fails shows no reading; the family retries on its own while collected.
            .catch { error -> if (error is CancellationException) throw error }
    }
}

/**
 * The generic half of the device destination, for a family with no panel of its own: the only
 * place that knows [GenericDevicePanelViewModel]. It owns the Health Connect request for the
 * grants the waiting measurements need, and draws [content] with the panel.
 */
@Composable
fun GenericDevicePanelRoute(
    features: Set<DeviceFeature>,
    viewModel: GenericDevicePanelViewModel = hiltViewModel(),
    content: @Composable (WatchDevicePanel) -> Unit,
) {
    val streams = DeviceFeature.LIVE_HEART_RATE in features
    val backlog by viewModel.backlog.collectAsStateWithLifecycle()
    val grantLauncher = rememberHealthConnectPermissionLauncher(onGranted = viewModel::retry)
    content(
        WatchDevicePanel(
            afterHeader = {
                if (streams) {
                    val bpm by viewModel.liveHeartRateBpm.collectAsStateWithLifecycle()
                    LiveHeartRateCard(bpm)
                }
                backlog?.let { waiting -> BacklogCard(waiting, onGrant = { grantLauncher.launch(waiting.withheld) }) }
            },
        ),
    )
}

/** The heart rate the device streams while the screen is open. */
@Composable
internal fun LiveHeartRateCard(bpm: Int?) {
    SectionHeader(stringResource(R.string.device_live_section))
    OpenWatchCard {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
            Text(text = stringResource(R.string.settings_sensors_capability_heart_rate), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = bpm?.let { stringResource(R.string.settings_watch_live_now, it) } ?: stringResource(R.string.device_live_waiting),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
    }
}

/** Readings the device handed over that Health Connect has not taken yet, and why. */
@Composable
internal fun BacklogCard(backlog: MeasurementOutcome, onGrant: () -> Unit) {
    if (backlog.pending == 0) return
    OpenWatchCard {
        Column(modifier = Modifier.fillMaxWidth().padding(Spacing.lg)) {
            val waiting = pluralStringResource(R.plurals.device_readings_waiting, backlog.pending, backlog.pending)
            if (backlog.withheld.isNotEmpty()) {
                InlineGrantPrompt(
                    message = stringResource(R.string.device_readings_waiting_grant, waiting),
                    actionLabel = stringResource(R.string.action_grant_permission),
                    onGrant = onGrant,
                )
            } else {
                Text(text = waiting, style = MaterialTheme.typography.bodyMedium)
                backlog.failure?.toScreenError().resolve()?.let { reason ->
                    Text(
                        text = reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun GenericDevicePanelPreview() {
    OpenWatchTheme {
        Column {
            LiveHeartRateCard(bpm = 128)
            BacklogCard(MeasurementOutcome(pending = 3, withheld = setOf("android.permission.health.WRITE_HEART_RATE")), onGrant = {})
        }
    }
}

/** Keeps the live stream through a rotation; longer, and the device's link stays up for nothing. */
private const val LIVE_STOP_TIMEOUT_MS = 5_000L
