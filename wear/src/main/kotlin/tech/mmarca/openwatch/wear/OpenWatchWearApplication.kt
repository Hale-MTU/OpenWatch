package tech.mmarca.openwatch.wear

import android.app.Application
import android.content.Context

/**
 * The watch app's process, and the owner of what lives as long as it does:
 * the [WearGraph], with the one [MetricStore]. The service and its recorders,
 * the activity, the dashboard and the tiles all run in this process; one open
 * helper means one connection pool, so a write never waits on a second
 * helper's lock and every reader sees the same file.
 */
class OpenWatchWearApplication : Application() {

    /** Built on first use and never torn down: it lives as long as the process. */
    internal val graph: WearGraph by lazy { WearGraph(this) }
}

/** The process's one [WearGraph]. */
internal val Context.wearGraph: WearGraph
    get() = (applicationContext as OpenWatchWearApplication).graph
