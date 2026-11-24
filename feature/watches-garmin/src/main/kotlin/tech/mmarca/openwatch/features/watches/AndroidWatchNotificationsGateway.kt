package tech.mmarca.openwatch.features.watches

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import tech.mmarca.openwatch.devices.garmin.GarminLog
import tech.mmarca.openwatch.devices.garmin.GarminNotificationBridge

/** The real gateway. */
class AndroidWatchNotificationsGateway @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bridge: GarminNotificationBridge,
) : WatchNotificationsGateway {

    override fun isNotificationAccessGranted(): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    override fun openNotificationAccessSettings() {
        try {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (error: Exception) {
            GarminLog.log("[GARMIN-NOTIFY] could not open notification settings: $error")
        }
    }

    // The launcher <queries> entry is the app's manifest's, which this module's lint does not see.
    @SuppressLint("QueryPermissionsNeeded")
    override fun listLaunchableApps(): List<InstalledApp> {
        val manager = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(intent, 0)
            .mapNotNull { resolved ->
                val info = resolved.activityInfo?.applicationInfo ?: return@mapNotNull null
                InstalledApp(
                    packageName = info.packageName,
                    label = manager.getApplicationLabel(info).toString(),
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override fun pushConfiguration() {
        // The listener and the bridge read the store itself; nothing is copied.
        bridge.onConfigChanged()
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface WatchNotificationsModule {
    @Binds
    @Singleton
    fun bindWatchNotificationsGateway(
        implementation: AndroidWatchNotificationsGateway,
    ): WatchNotificationsGateway
}
