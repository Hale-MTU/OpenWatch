// The phone app's pure models: the device, sleep-minute, scale and activity models under
// `domain/model`, the unit and theme preferences under `domain/preferences`, and the
// coordinate parsing under `core/geo`; the platform sources' plain values the device
// integrations read (`devices/weather`, `devices/notifications`); and `runSuspendCatching`, the
// injected dispatchers and the application scope's qualifier (`core/performance`). Plain Kotlin
// on the JDK, with the app's package names, so the device modules can depend on it. The
// calculations are health-calculations', shared with OpenVitals.
plugins {
    id("openwatch.jvm.library")
    // Android lint checked these files in :domain; it still does, with the same rules (build-logic/).
    id("com.android.lint")
}

dependencies {
    // The activity models carry health-calculations' route, lap and pause types in their
    // public signatures (the health-calculations/ submodule, a composite build).
    api("tech.mmarca.healthcalculations:health-calculations")
    // DispatcherProvider hands out coroutine dispatchers; ApplicationScope is a javax.inject
    // qualifier, read by Dagger in the modules that inject it.
    api(libs.kotlinx.coroutines.core)
    implementation(libs.javax.inject)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
