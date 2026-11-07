package tech.mmarca.openwatch.devices.core.pairing

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Build
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * CompanionDeviceManager association. For a watch it lets the OS raise
 * this app's priority while the watch is in range, which a long sync needs.
 * Optional: the user can decline, and presence needs API 31+, so every
 * method returns "no association" rather than throwing. The device address
 * is never logged. Association needs an attached Activity.
 */
@Singleton
class CompanionDevicePairing @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
) {
    /** Launcher + pending continuation for the association dialog's result. */
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var attachedActivityId: Int? = null
    /** Completed with the dialog's result Intent on consent, null on anything else. */
    private var pendingOutcome: CompletableDeferred<Intent?>? = null

    /** The address being associated, so a success can start presence observation. */
    private var pendingAddress: String? = null

    private fun manager(): CompanionDeviceManager? =
        applicationContext.getSystemService(CompanionDeviceManager::class.java)

    // Activity lifecycle.

    /** MainActivity attaches in onCreate. Without it [associate] can never show its dialog. */
    fun attachToActivity(activity: Activity) {
        val componentActivity = activity as? ComponentActivity
        if (componentActivity == null) {
            Log.w(TAG, "activity is not a ComponentActivity; companion association unavailable")
            return
        }
        detachFromActivity()
        attachedActivityId = System.identityHashCode(activity)
        launcher =
            componentActivity.activityResultRegistry.register(
                "tech.mmarca.openwatch.devices.core.pairing.companion",
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { result: ActivityResult ->
                val address = pendingAddress
                val allowed = result.resultCode == Activity.RESULT_OK
                if (allowed && address != null) startObservingPresence(address)
                Log.i(TAG, "companion association allowed=$allowed")
                // A consent with no data still counts: the address was known beforehand.
                resolvePending(if (allowed) result.data ?: Intent() else null)
            }
    }

    /** Detaches only the Activity that is attached, so a late onDestroy cannot unhook a newer one. */
    fun detachFromActivity(activity: Activity) {
        if (attachedActivityId == System.identityHashCode(activity)) detachFromActivity()
    }

    fun detachFromActivity() {
        attachedActivityId = null
        launcher?.unregister()
        launcher = null
        // A dialog in flight when the Activity goes away can never report back.
        resolvePending(null)
    }

    // API.

    /**
     * Asks the OS to associate [address], showing the system dialog. True when
     * allowed; false on decline and on every degraded path.
     */
    suspend fun associate(
        address: String,
        @Suppress("UNUSED_PARAMETER") displayName: String? = null,
        filter: CompanionFilter = CompanionFilter.BLE,
    ): Boolean {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            Log.w(TAG, "associate: invalid address")
            return false
        }
        val manager = manager()
        if (manager == null) {
            Log.i(TAG, "associate: CompanionDeviceManager unavailable")
            return false
        }
        // Already associated: the OS never calls back for a repeat request.
        if (isAssociated(address)) {
            Log.i(TAG, "associate: already associated")
            startObservingPresence(address)
            return true
        }
        // Filter by address over a BLE scan. A Wear OS watch may be registered under its
        // Classic bond address, which no BLE scan sees: without the Classic filter
        // (BLE_OR_CLASSIC) the dialog searches forever.
        val builder =
            AssociationRequest.Builder()
                .addDeviceFilter(
                    BluetoothLeDeviceFilter.Builder()
                        .setScanFilter(ScanFilter.Builder().setDeviceAddress(address).build())
                        .build(),
                )
                .setSingleDevice(true)
        if (filter == CompanionFilter.BLE_OR_CLASSIC) {
            builder.addDeviceFilter(BluetoothDeviceFilter.Builder().setAddress(address).build())
        }
        pendingAddress = address
        return request(builder.build()) != null
    }

    /**
     * Runs one association request through the system dialog. Null unless
     * the user consented. The wait for the dialog is bounded: Android 12
     * never calls back when its scan finds nothing. The dialog itself is not,
     * since the user is in it.
     */
    private suspend fun request(request: AssociationRequest): Intent? {
        val manager = manager() ?: return null
        val activeLauncher = launcher
        if (activeLauncher == null) {
            Log.w(TAG, "associate: no activity attached")
            return null
        }
        if (pendingOutcome != null) {
            Log.w(TAG, "associate: a request is already in flight")
            return null
        }

        val found = CompletableDeferred<Boolean>()
        val outcome = CompletableDeferred<Intent?>()
        pendingOutcome = outcome
        Log.i(TAG, "associate: requesting association")
        try {
            manager.associate(
                request,
                object : CompanionDeviceManager.Callback() {
                    override fun onDeviceFound(intentSender: IntentSender) {
                        // A find after the caller gave up must not pop the dialog for nobody.
                        if (pendingOutcome !== outcome) return
                        found.complete(true)
                        try {
                            activeLauncher.launch(IntentSenderRequest.Builder(intentSender).build())
                        } catch (e: Exception) {
                            Log.w(TAG, "associate: launch failed: ${e.message}")
                            resolvePending(null)
                        }
                    }

                    override fun onFailure(error: CharSequence?) {
                        // Usually the device was not seen in the scan window. Not fatal.
                        Log.w(TAG, "associate: failed: $error")
                        found.complete(false)
                        resolvePending(null)
                    }
                },
                null,
            )
        } catch (e: Exception) {
            // `associate` throws synchronously when the platform refuses outright.
            Log.w(TAG, "associate: request refused: ${e.message}")
            resolvePending(null)
            return null
        }

        try {
            val wasFound = withTimeoutOrNull(FIND_TIMEOUT_MILLIS) { found.await() }
            if (wasFound != true) {
                resolvePending(null)
                return null
            }
            return outcome.await()
        } catch (error: CancellationException) {
            resolvePending(null)
            throw error
        }
    }

    fun isAssociated(address: String): Boolean {
        val manager = manager() ?: return false
        return try {
            @Suppress("DEPRECATION")
            manager.associations.any { it.equals(address, ignoreCase = true) }
        } catch (e: Exception) {
            Log.w(TAG, "isAssociated: ${e.message}")
            false
        }
    }

    fun disassociate(address: String) {
        val manager = manager() ?: return
        try {
            stopObservingPresence(address)
            @Suppress("DEPRECATION")
            manager.disassociate(address)
            Log.i(TAG, "disassociated")
        } catch (e: Exception) {
            // Nothing to forget is not a failure.
            Log.i(TAG, "disassociate: ${e.message}")
        }
    }

    // Presence observation (API 31+): what wakes OpenWatchCompanionDeviceService.

    private fun startObservingPresence(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = manager() ?: return
        try {
            @Suppress("DEPRECATION")
            manager.startObservingDevicePresence(address)
        } catch (e: Exception) {
            Log.w(TAG, "startObservingDevicePresence: ${e.message}")
        }
    }

    private fun stopObservingPresence(address: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val manager = manager() ?: return
        try {
            @Suppress("DEPRECATION")
            manager.stopObservingDevicePresence(address)
        } catch (e: Exception) {
            Log.i(TAG, "stopObservingDevicePresence: ${e.message}")
        }
    }

    private fun resolvePending(result: Intent?) {
        val outcome: CompletableDeferred<Intent?>?
        synchronized(this) {
            outcome = pendingOutcome
            pendingOutcome = null
            pendingAddress = null
        }
        outcome?.complete(result)
    }

    internal companion object {
        const val TAG = "OpenWatchCompanion"

        /** Android's own scan gives up after 20 s; Android 12 then says nothing at all. */
        private const val FIND_TIMEOUT_MILLIS = 30_000L
    }
}
