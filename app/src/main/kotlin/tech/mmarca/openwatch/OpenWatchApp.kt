package tech.mmarca.openwatch

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlin.concurrent.thread
import tech.mmarca.openwatch.core.performance.AppForegroundGate

@HiltAndroidApp
class OpenWatchApp : Application() {

    @Inject lateinit var appForegroundGate: AppForegroundGate

    // Lazy, so Hilt does not build their graph inside super.onCreate(). See AppStartupActors.
    @Inject lateinit var startupActors: dagger.Lazy<AppStartupActors>

    override fun onCreate() {
        super.onCreate()
        appForegroundGate.registerProcessLifecycle(ProcessLifecycleOwner.get())
        thread(name = "app-startup") {
            startupActors.get().start()
        }
    }
}
