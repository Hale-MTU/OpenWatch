// Settings and the body profile: the sync switch, the respiratory rate, notification access, the
// health data list and the privacy policy, the version; the declared body with Health Connect's
// weight and height on top, and its exchange with OpenVitals over OpenLink. In the app's package
// features/settings.
plugins {
    id("openwatch.android.library")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tech.mmarca.openwatch.features.settings"
    // SDK levels, the ci and nightly build types and test sizing: openwatch.android.library.

    defaultConfig {
        minSdk = 26
    }

    buildFeatures {
        compose = true
    }

    lint {
        // As the app's: Weblate translates the strings, a language at a time.
        disable += setOf("MissingTranslation")
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":link-host"))
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
    testImplementation(testFixtures(project(":devices:core")))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
