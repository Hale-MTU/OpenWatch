package tech.mmarca.openwatch.features.scales

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.mmarca.openwatch.core.presentation.ScreenError
import tech.mmarca.openwatch.core.presentation.UnitFormatter
import tech.mmarca.openwatch.core.presentation.toScreenError
import tech.mmarca.openwatch.healthconnect.HealthConnectGateway
import tech.mmarca.openwatch.data.repository.contract.ScaleWeighInRepository
import tech.mmarca.openwatch.devices.xiaomi.ScaleListenerStatus
import tech.mmarca.openwatch.devices.xiaomi.ScaleWeighInWriter
import tech.mmarca.openwatch.devices.xiaomi.ScaleWriteFailure
import tech.mmarca.openwatch.devices.xiaomi.ScaleWritePermissions
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleListener
import tech.mmarca.openwatch.devices.xiaomi.XiaomiScaleStore
import tech.mmarca.healthcalculations.insights.BodyCompositionInput
import tech.mmarca.openwatch.domain.model.ScaleWeighIn

/** The last weigh-in, ready to show. A null line was not measured, or could not be estimated. */
@Immutable
data class ScaleWeighInDisplay(
    val time: Instant,
    val weight: String,
    val heartRate: String?,
    val bodyFat: String?,
    val leanMass: String?,
    val bodyWater: String?,
    /** No impedance arrived, so there is nothing to estimate from. */
    val weightOnly: Boolean,
)

@Immutable
data class ScaleDeviceUiState(
    /** Null while loading and after removal: the screen shows its no-data state. */
    val name: String? = null,
    val address: String? = null,
    /** The scale's user slot this person weighs in under; null until the first weigh-in. */
    val profile: Int? = null,
    val keyRejected: Boolean = false,
    val listenerStatus: ScaleListenerStatus = ScaleListenerStatus.OFF,
    /** Android wakes the app when someone steps on the scale, so it is heard with the app closed. */
    val wokenBySystem: Boolean = false,
    /** The last request for that found no scale, or was declined. */
    val systemWakeFailed: Boolean = false,
    val lastWeighIn: ScaleWeighInDisplay? = null,
    val hasPendingWeighIns: Boolean = false,
    val writeFailure: ScaleWriteFailure? = null,
    /** Health Connect write permissions the scale could use and does not have. */
    val missingWritePermissions: Set<String> = emptySet(),
    val missingProfileInputs: Set<BodyCompositionInput> = emptySet(),
    /** The scale filed a weigh-in under another of its user slots. */
    val otherProfileIgnored: Boolean = false,
    val screenError: ScreenError? = null,
    /** The new-key dialog is open; it closes only on a text that is a key. */
    val keyDialogOpen: Boolean = false,
    /** The last text saved in that dialog was not a key. */
    val keyInputInvalid: Boolean = false,
    /** The scale was removed: the screen goes back. */
    val removed: Boolean = false,
)

private data class ScaleDeviceLocalState(
    val wokenBySystem: Boolean = false,
    val systemWakeFailed: Boolean = false,
    val missingWritePermissions: Set<String> = emptySet(),
    val missingProfileInputs: Set<BodyCompositionInput> = emptySet(),
    val screenError: ScreenError? = null,
    val keyDialogOpen: Boolean = false,
    val keyInputInvalid: Boolean = false,
    val removed: Boolean = false,
)

/** One scale, and everything about it. The listening and the saving go on without this screen. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ScaleDeviceViewModel @Inject constructor(
    private val store: XiaomiScaleStore,
    private val listener: XiaomiScaleListener,
    private val writer: ScaleWeighInWriter,
    weighIns: ScaleWeighInRepository,
    private val healthConnect: HealthConnectGateway,
    private val unitFormatter: UnitFormatter,
) : ViewModel() {

    private val local = MutableStateFlow(ScaleDeviceLocalState())
    private var latestWeighIn: ScaleWeighIn? = null

    private val lastWeighIn = weighIns.latest.mapLatest { weighIn ->
        latestWeighIn = weighIn
        weighIn?.toDisplay()
    }

    val uiState: StateFlow<ScaleDeviceUiState> = combine(
        store.config,
        listener.status,
        lastWeighIn,
        weighIns.pendingCount,
        local,
    ) { config, status, last, pending, local ->
        ScaleDeviceUiState(
            name = if (config.isSetUp) config.name ?: config.address else null,
            address = config.address,
            profile = config.profile,
            keyRejected = config.keyRejected,
            listenerStatus = status,
            wokenBySystem = local.wokenBySystem,
            systemWakeFailed = local.systemWakeFailed,
            lastWeighIn = last,
            hasPendingWeighIns = pending > 0,
            writeFailure = config.writeFailure,
            missingWritePermissions = local.missingWritePermissions,
            missingProfileInputs = local.missingProfileInputs,
            otherProfileIgnored = config.ignoredProfile != null,
            screenError = local.screenError,
            keyDialogOpen = local.keyDialogOpen,
            keyInputInvalid = local.keyInputInvalid,
            removed = local.removed,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScaleDeviceUiState())

    /** On every return: a fast scan while on screen, another try at pending writes, and the grants as they stand. */
    fun refresh() {
        if (!store.config.value.isSetUp) return
        listener.arm(restart = true)
        listener.retryPending()
        viewModelScope.launch {
            val granted = try {
                healthConnect.grantedPermissions()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                // Health Connect cannot say. The write path reports what it then finds.
                ScaleWritePermissions
            }
            val missingInputs = writer.missingProfileInputs()
            local.update {
                it.copy(
                    missingWritePermissions = ScaleWritePermissions - granted,
                    missingProfileInputs = missingInputs,
                    wokenBySystem = listener.isWokenBySystem(),
                )
            }
        }
    }

    fun rename(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        listener.rename(trimmed)
    }

    fun openKeyDialog() {
        local.update { it.copy(keyDialogOpen = true, keyInputInvalid = false) }
    }

    fun dismissKeyDialog() {
        local.update { it.copy(keyDialogOpen = false, keyInputInvalid = false) }
    }

    /** The text in the key dialog changed: the last complaint no longer applies. */
    fun onKeyEdited() {
        local.update { it.copy(keyInputInvalid = false) }
    }

    /** A new key for the same scale. A text that is not a key keeps the dialog open, marked invalid. */
    fun changeKey(text: String) {
        val key = XiaomiScaleStore.parseBindKey(text)
        if (key == null) {
            local.update { it.copy(keyInputInvalid = true) }
            return
        }
        listener.changeKey(key)
        local.update { it.copy(keyDialogOpen = false, keyInputInvalid = false) }
    }

    /** Asks Android to watch for the scale again, for a user who revoked it. The scale has to be awake. */
    fun allowSystemWake() {
        viewModelScope.launch {
            val allowed = listener.allowSystemWake()
            local.update { it.copy(wokenBySystem = allowed, systemWakeFailed = !allowed) }
        }
    }

    /** The scale moved this person to another of its user slots. Later weigh-ins from it are theirs. */
    fun claimIgnoredProfile() {
        val ignored = store.config.value.ignoredProfile ?: return
        store.setProfile(ignored.profile)
    }

    fun deleteLastWeighIn() {
        val weighIn = latestWeighIn ?: return
        viewModelScope.launch {
            try {
                writer.delete(weighIn)
                local.update { it.copy(screenError = null) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                local.update { it.copy(screenError = error.toScreenError(logTag = "Scales")) }
            }
        }
    }

    /** Stops listening and forgets the scale and its key. Saved weigh-ins stay. */
    fun remove() {
        listener.forget()
        local.update { it.copy(removed = true) }
    }

    private suspend fun ScaleWeighIn.toDisplay(): ScaleWeighInDisplay? {
        val weightKg = reading.weightKg ?: return null
        val composition = writer.compositionOf(this)
        return ScaleWeighInDisplay(
            time = time,
            weight = unitFormatter.weight(weightKg).text,
            heartRate = reading.heartRateBpm?.let { unitFormatter.heartRate(it.toLong()).text },
            bodyFat = composition?.let { unitFormatter.percent(it.bodyFatPercent).text },
            leanMass = composition?.let { unitFormatter.bodyMass(it.fatFreeMassKg).text },
            bodyWater = composition?.let { unitFormatter.bodyMass(it.bodyWaterKg).text },
            weightOnly = reading.impedanceLowOhm == null,
        )
    }
}
