// The OpenLink host: the one exported entry for other apps (OpenWatchLinkService, declared in
// this module's manifest), the capabilities OpenWatch offers through it (the devices and their
// sync, the body profile, recording sessions and live readings), the trusted first-party apps,
// the streaming notification and the profile's sync both ways. In the app's package `link`.
// The library is the openlink/ submodule's `host`; the trust store and the binder are built in
// the app's di/LinkModule, which also says which screen a consent request opens.
plugins {
    id("openwatch.android.library")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tech.mmarca.openwatch.link"
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
    // Public signatures carry OpenLink's types (the host, its sessions and records), the
    // registry and the body profile's contract.
    api("tech.mmarca.openlink:host")
    api(project(":core:data"))
    // The device families, their live readings and the sync controller a capability drives.
    implementation(project(":devices:core"))
    // The streaming notification joins the devices' names in the phone's language.
    implementation(project(":core:ui"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(testFixtures(project(":core:data")))
    testImplementation(testFixtures(project(":core:devices-api")))
    testImplementation(testFixtures(project(":devices:core")))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
