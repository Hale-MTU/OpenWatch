package tech.mmarca.openwatch.devices.core.sync

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** The [SyncWakeLock] of the app: a partial wake lock with the radio's timeout. */
class AndroidSyncWakeLock @Inject constructor(
    @ApplicationContext private val context: Context,
) : SyncWakeLock {
    override suspend fun <T> holding(block: suspend () -> T): T {
        val lock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            ?.apply { setReferenceCounted(false) }
        lock?.acquire(TIMEOUT_MILLIS)
        return try {
            block()
        } finally {
            if (lock?.isHeld == true) lock.release()
        }
    }

    private companion object {
        const val TAG = "OpenWatch:sync"

        /** The radio's own limit for a whole sync, so the lock never outlives it. */
        const val TIMEOUT_MILLIS = 30 * 60_000L
    }
}
