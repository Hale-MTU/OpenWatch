package tech.mmarca.openwatch.devices.core.sync

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
