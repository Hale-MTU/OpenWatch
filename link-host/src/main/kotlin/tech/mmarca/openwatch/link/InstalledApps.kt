package tech.mmarca.openwatch.link

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** Which apps are installed, for a state holder that holds no `Context`. */
interface InstalledApps {

    /** The app's name as the launcher shows it, or null when it is not installed (or not visible). */
    fun labelIfInstalled(packageName: String): String?

    /** Emits each time an app is installed, updated or removed. */
    val changes: Flow<Unit>
}

/** [InstalledApps] from the package manager and its package broadcasts. */
class SystemInstalledApps(private val context: Context) : InstalledApps {

    override fun labelIfInstalled(packageName: String): String? = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(packageName, 0)
        }
        pm.getApplicationLabel(info).toString()
    }.getOrNull()

    override val changes: Flow<Unit> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(Unit)
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        // The system sends these; nothing else may.
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }
}
