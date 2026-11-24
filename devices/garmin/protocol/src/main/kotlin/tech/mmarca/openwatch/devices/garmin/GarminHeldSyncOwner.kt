package tech.mmarca.openwatch.devices.garmin

/** Where a held companion link hands the files it pulls. One per watch. */
public interface GarminHeldSyncOwner {
    /** Dedup keys of files already imported, read at handoff time. */
    public fun alreadySyncedKeys(): Set<String>

    /** Persists one file. Throwing keeps the watch from archiving it. */
    public suspend fun keep(file: GarminDownloadedFile)

    /** A finished batch, every file already kept. May be empty. */
    public fun imported(files: List<GarminDownloadedFile>)

    /** The watch has data this link cannot list; run a full sync. */
    public fun needsFullSync()
}
