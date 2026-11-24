// The Xiaomi S400 scale's screens: Home's scale section with its add flow (the scan, the bind key,
// the name) and the scale's own destination (its weigh-ins and their records in Health Connect, its
// key, its listening), which it binds under the family's name into :feature:device's
// contributions (XiaomiScaleFeatureModule). In the app's package features/scales. It depends on its
// family's module and on no other.
plugins {
    id("openwatch.android.library")
    id("openwatch.robolectric")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tech.mmarca.openwatch.features.scales"
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
    implementation(project(":devices:xiaomi"))
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
    // The weigh-in table in memory.
    testImplementation(testFixtures(project(":devices:xiaomi")))
    testImplementation(testFixtures(project(":feature:device")))
    testImplementation(testFixtures(project(":core:data")))
    testImplementation(testFixtures(project(":devices:core")))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
