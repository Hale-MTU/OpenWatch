// The Wear OS watch's phone side: the RFCOMM link to the OpenWatch watch app (the session
// cache, the node port, the link store with the sealed tokens), the sync of every series the
// watch keeps and their Health Connect mapping, the onboarding, the family, and the words for
// the link's states. The protocol is :wearlink, shared with the watch app. It binds its own
// family and ports (WearOsModule). In the app's package `devices/wearos`. It depends on the
// shared device core and on no other device family.
plugins {
    id("openwatch.android.library")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tech.mmarca.openwatch.devices.wearos"
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
    // Public signatures carry the device contracts, the record sink and the link's own types
    // (the watch's preferences, its rows).
    api(project(":devices:core"))
    api(project(":wearlink"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(testFixtures(project(":devices:core")))
    testImplementation(testFixtures(project(":core:devices-api")))
    // The Wear OS sleep-minute fixtures, for the sync tests.
    testImplementation(testFixtures("tech.mmarca.healthcalculations:health-calculations"))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
