// The Garmin watches' platform half: the GATT client over the shared GATT link, the radio,
// notification and settings links, the stores on SharedPreferences, the music, navigation and
// find-phone relays, the file import into Health Connect (activities, wellness, sleep), the
// onboarding and the family. Its protocol is :devices:garmin:protocol. It binds its own family
// and ports (GarminModule), declares its manifest component and carries its notification's
// strings. In the app's package `devices/garmin`. It depends on the shared device core and on
// no other device family; the wellness and sleep-minute tables it writes through are the app's
// Room.
plugins {
    id("openwatch.android.library")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tech.mmarca.openwatch.devices.garmin"
    // SDK levels, the ci and nightly build types and test sizing: openwatch.android.library.

    defaultConfig {
        minSdk = 26
    }

    lint {
        // As the app's: Weblate translates the strings, a language at a time.
        disable += setOf("MissingTranslation")
    }
}

dependencies {
    // Public signatures carry the device contracts, the record sink, the platform sources and
    // the protocol's own types (files, settings, alarms, capabilities), which the watch screens use.
    api(project(":devices:core"))
    api(project(":devices:garmin:protocol"))
    // The FIT codec the wellness import reads with.
    implementation(project(":core:fit"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(testFixtures(project(":devices:core")))
    testImplementation(testFixtures(project(":core:devices-api")))
    // The record sink in memory, for the hydration widget's drinks through the measurement sink.
    testImplementation(testFixtures(project(":core:health-api")))
    // The FIT test bytes the wellness import tests build their files with.
    testImplementation(testFixtures(project(":devices:garmin:protocol")))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.truth)
    // Real org.json for local tests (the android.jar copy is a throwing stub).
    testImplementation(libs.org.json)
}
