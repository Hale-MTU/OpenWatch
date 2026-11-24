package tech.mmarca.openwatch.link

import android.app.PendingIntent

/**
 * Opens the app on its Connected apps screen: what a consent request and the streaming
 * notification lead to. The activity is the app's, so the app makes it (`di/LinkModule`).
 */
fun interface ConnectedAppsIntent {
    /** The pending intent under [requestCode], one per notification or asking app. */
    fun pendingIntent(requestCode: Int): PendingIntent
}
