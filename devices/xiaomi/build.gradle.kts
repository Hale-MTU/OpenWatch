// The Xiaomi S400 scale's platform half: the system scan that hears it, the receiver and the
// foreground service a weigh-in wakes, the listener and its worker, the store with the sealed
// bind key, and the pipeline from a frame to Room and Health Connect. Its codec is
// :devices:xiaomi:protocol. It binds its own family and ports (XiaomiScaleModule), declares its
// manifest components and carries the strings its notification shows. In the app's package
// `devices/xiaomi`. It depends on the shared device core and on no other device family; the
// weigh-in table it writes through (ScaleWeighInRepository) is the app's Room.
plugins {
    id("openwatch.android.library")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tech.mmarca.openwatch.devices.xiaomi"
    // SDK levels, the ci and nightly build types and test sizing: openwatch.android.library.

    defaultConfig {
        minSdk = 26
    }

    // FakeScaleWeighInRepository, for the app's tests of the Scales screens.
    testFixtures {
        enable = true
    }

    lint {
        // As the app's: Weblate translates the strings, a language at a time.
        disable += setOf("MissingTranslation")
    }
}

dependencies {
    // Public signatures carry the device contracts, the companion pairing, the record sink and
    // the S400's frames and readings.
    api(project(":devices:core"))
    api(project(":devices:xiaomi:protocol"))
    // SecretSeal, which seals the bind key at rest.
    implementation(project(":wearlink"))
    implementation(libs.androidx.core.ktx)
    // The half-hourly safety net that arms the scan again.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(testFixtures(project(":devices:core")))
    testImplementation(testFixtures(project(":core:devices-api")))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
