// The screens every device family shares, which name none: the device route DeviceDetail and the
// device screen (the header, the sync and its schedule, the live sensors, removal) with the generic
// panel; Home's watch list with the add-a-watch flow and its "Other devices" section with the
// app's own add flow; a sync's summary; and the contributions a family's screens plug in through
// (features/device/DeviceContributions.kt). In the app's packages features/device and
// features/watches. A family's screens are modules of their own that depend on this one.
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
    namespace = "tech.mmarca.openwatch.features.device"
    // SDK levels, the ci and nightly build types and test sizing: openwatch.android.library.

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    testFixtures {
        enable = true
    }

    // Robolectric reads the module's merged resources for the screens' strings.
    testOptions.unitTests.isIncludeAndroidResources = true

    lint {
        // As the app's: Weblate translates the strings, a language at a time.
        disable += setOf("MissingTranslation")
    }
}

dependencies {
    // Public signatures carry the sync controller's state and the device contracts.
    api(project(":devices:core"))
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
    // routeHandle(): the SavedStateHandle Navigation gives a route, for the tests of these screens
    // and of a family's, which read their routes back with toRoute.
    testFixturesApi(libs.androidx.navigation.compose)
    // Compose at the BOM's versions there too, as Navigation brings it.
    testFixturesImplementation(composeBom)

    // The main dispatcher swapped for the tests' (MainDispatcherRule).
    testImplementation(testFixtures(project(":core:model")))
    // The device screen's tests run on the two watch families as they are.
    testImplementation(project(":devices:garmin"))
    testImplementation(project(":devices:wearos"))
    testImplementation(testFixtures(project(":core:data")))
    testImplementation(testFixtures(project(":core:devices-api")))
    testImplementation(testFixtures(project(":devices:core")))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
