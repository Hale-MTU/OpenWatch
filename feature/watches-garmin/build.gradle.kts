// The Garmin watches' screens: their panel in the device screen (find, music, the ephemeris, the
// switches) and their pairing in the add-a-watch flow, bound under the family's name into
// :feature:device's contributions (GarminFeatureModule); and the screens the panel opens: the
// watch's data, its notifications' apps, its alarms, its settings tree and the point sent to it,
// whose routes this module declares (navigation/GarminRoutes.kt). In the app's packages
// features/watches and features/watches/garmin. It depends on its family's module and on no other.
plugins {
    id("openwatch.android.library")
    id("openwatch.robolectric")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    // @Serializable navigation routes.
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "tech.mmarca.openwatch.features.watches.garmin"
    // SDK levels, the ci and nightly build types and test sizing: openwatch.android.library.

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    // Robolectric reads the module's merged resources for the screens' strings.
    testOptions.unitTests.isIncludeAndroidResources = true

    lint {
        // As the app's: Weblate translates the strings, a language at a time.
        disable += setOf("MissingTranslation")
    }
}

dependencies {
    implementation(project(":feature:device"))
    implementation(project(":devices:garmin"))
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    // Navigation at the app's version: hiltViewModel's library names an older one.
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)


    // The main dispatcher swapped for the tests' (MainDispatcherRule).
    testImplementation(testFixtures(project(":core:model")))
    testImplementation(testFixtures(project(":feature:device")))
    testImplementation(testFixtures(project(":core:data")))
    testImplementation(testFixtures(project(":devices:core")))
    // A sync port that records what it was asked.
    testImplementation(testFixtures(project(":core:devices-api")))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
