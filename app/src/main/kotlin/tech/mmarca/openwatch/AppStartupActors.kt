package tech.mmarca.openwatch

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the app starts at every process start that the first screen does not wait for: each
 * module contributes its own [StartupActor] (`:core:devices-api`) `@IntoSet`, a device family's
 * in its own Hilt module, so a new one starts without a change here.
 *
 * The Application asks for this through `dagger.Lazy` and builds it on a background
 * thread, so the device graph is not pulled into `super.onCreate()` on the main thread.
 */
@Singleton
class AppStartupActors @Inject constructor(
    private val actors: Set<@JvmSuppressWildcards StartupActor>,
) {
    /** The actors' names, in the order they start. */
    val names: List<String> get() = ordered().map { it.name }

    fun start() {
        // Each on its own: this runs at every process start, a scale broadcast or a weather
        // broadcast included, so one actor that throws must not stop the others or crash the
        // process every time it starts.
        for ((name, start) in ordered().map { it.name to it.start }) {
            try {
                start()
            } catch (error: Exception) {
                // The JVM's Log stub throws in a unit test; a log line must not undo the catch.
                runCatching { Log.w(TAG, "start-up actor failed: $name", error) }
            }
        }
    }

    private fun ordered(): List<StartupActor> = actors.sortedBy { it.name }

    private companion object {
        const val TAG = "AppStartupActors"
    }
}
