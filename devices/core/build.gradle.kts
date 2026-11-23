// The platform half every device family shares, on Android: the radio lease, the GATT link
// (dial, redial, MTU, discovery, notifications, writes) and the BLE scanner, companion pairing
// and presence (the app's primary companion service), and the sync controller, its schedule and
// its worker. In the app's package `devices/core`. The contracts are :core:devices-api's; each
// family's own half is its module, :devices:<vendor>, which depends on this one and on no
// other family.
plugins {
    id("openwatch.android.library")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tech.mmarca.openwatch.devices.core"
    // SDK levels, the ci and nightly build types and test sizing: openwatch.android.library.

    defaultConfig {
        minSdk = 26
        // DeviceLog.isEnabled: the device layer logs to logcat in a debuggable build only.
        buildConfigField("boolean", "DEVICE_DEBUG_LOGS", "false")
    }

    buildTypes {
        getByName("debug") {
            // The app's debug build is debuggable, except the one the release pipeline
            // publishes (openwatch.android.application); a library's own BuildConfig.DEBUG
            // does not follow that, so this field does.
            val published = providers.environmentVariable("OPENWATCH_PUBLISHED_DEBUG_APK").orNull == "true"
            buildConfigField("boolean", "DEVICE_DEBUG_LOGS", (!published).toString())
        }
        // Made from debug by the convention plugin before the field above was set.
        getByName("ci") {
            buildConfigField("boolean", "DEVICE_DEBUG_LOGS", "true")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    // What the device modules' tests and the app's share: SharedPreferences in memory, and a
    // SecretSeal over a software key.
    testFixtures {
        enable = true
    }
}

dependencies {
    // Public signatures carry the device contracts (the sync port, the pairing port, the
    // registry) and Health Connect's sleep session record (EstimatedSleepRecord).
    api(project(":core:devices-api"))
    api(project(":core:health-api"))
    implementation(libs.androidx.core.ktx)
    // The companion dialog's result comes back through an activity result launcher.
    implementation(libs.androidx.activity)
    // The scheduled sync's worker and its schedule.
    implementation(libs.androidx.work.runtime.ktx)
    // The companion service and the worker's entry point are Hilt's; the rest is constructor injection.
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // testSeal() hands out the link's SecretSeal.
    testFixturesApi(project(":wearlink"))

    testImplementation(testFixtures(project(":core:devices-api")))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
