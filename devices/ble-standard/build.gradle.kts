// The devices that speak the Bluetooth SIG's standard health profiles, as one device family:
// a heart rate strap, a blood pressure monitor, a weight scale and a glucose meter. Its bytes are
// :devices:ble-standard:protocol's; here are the link over the shared GattLink, the pairing the
// app's add flow runs, the strap's live stream, the collector a pushed device's wake starts, and
// the Health Connect mapping of what they push. In the app's package `devices/blestandard`.
// It binds its own family (BleStandardModule), depends on the shared device core and on no
// other family, and declares no manifest component.
plugins {
    id("openwatch.android.library")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tech.mmarca.openwatch.devices.blestandard"
    // SDK levels, the ci and nightly build types and test sizing: openwatch.android.library.

    defaultConfig {
        minSdk = 26
    }
}

dependencies {
    // Public signatures carry the device contracts, the measurement sink and the profiles' values.
    api(project(":devices:core"))
    api(project(":devices:ble-standard:protocol"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(testFixtures(project(":core:devices-api")))
    testImplementation(testFixtures(project(":core:health-api")))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
