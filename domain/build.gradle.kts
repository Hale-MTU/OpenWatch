// App-local code with no dependency on the rest of the phone app: the device,
// sleep-minute and scale models and the unit preferences under `domain`, and the
// FIT and geo primitives under `core`. Packages keep their names; :app depends on
// this module. The calculations are health-calculations', shared with OpenVitals.
//
// An Android library rather than plain Kotlin: a handful of files use Health
// Connect record constants and the platform's locale data, and Android lint
// then still checks the module, on its own task beside the app's. The module
// exists for build time: lint and the unit tests run here in parallel with
// the app's, and a change elsewhere leaves this module's results cached.
plugins {
    id("openwatch.android.library")
}

android {
    namespace = "tech.mmarca.openwatch.domain"
    // SDK levels, the ci and nightly build types and test sizing: openwatch.android.library.

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    // Public types of this module reference these.
    api(project(":wearlink"))
    api(libs.health.connect.client)
    // The calculations and the models they read, shared with OpenVitals
    // (the health-calculations/ submodule, a composite build). The app uses them too.
    api("tech.mmarca.healthcalculations:health-calculations")

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.org.json)
}

