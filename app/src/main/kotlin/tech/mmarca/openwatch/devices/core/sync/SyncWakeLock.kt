package tech.mmarca.openwatch.devices.core.sync

import android.content.Context
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Keeps the CPU awake while a sync runs that no job holds awake: one tapped on a screen, asked
 * for by another app over OpenLink, or announced by the watch. A scheduled sync needs none,
 * WorkManager holds one for its job. Always with a timeout, so a sync that hangs cannot hold
 * the phone awake.
 */
interface SyncWakeLock {
    suspend fun <T> holding(block: suspend () -> T): T

    /** Holds nothing: for tests. */
    object None : SyncWakeLock {
        override suspend fun <T> holding(block: suspend () -> T): T = block()
    }
}

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
