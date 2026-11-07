package tech.mmarca.openwatch.devices.core.pairing

import android.companion.CompanionDeviceService
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Bound by the OS while an associated device is in range. For a watch the
 * binding is the feature: it raises the process priority, which keeps a
 * minutes-long sync from being killed. API 31+.
 */
@RequiresApi(Build.VERSION_CODES.S)
class OpenWatchCompanionDeviceService : CompanionDeviceService() {

    // The address is not logged: a MAC identifies the person.
    override fun onDeviceAppeared(address: String) {
        Log.d(CompanionDevicePairing.TAG, "companion device appeared")
    }

    override fun onDeviceDisappeared(address: String) {
        Log.d(CompanionDevicePairing.TAG, "companion device disappeared")
    }
}
