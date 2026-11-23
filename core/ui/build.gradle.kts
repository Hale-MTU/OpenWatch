// What every screen shares: the theme (ui/theme), the shared components every screen uses
// instead of its own copy (ui/components: the cards, rows, dialogs, the app bar state, the
// error content, the runtime and Health Connect permission launchers), the formatters and
// ScreenError (core/presentation) and the OS permission checks (core/permissions). In the
// app's packages. The screens are the :feature:* modules; nothing here knows a device family.
plugins {
    id("openwatch.android.library")
    id("openwatch.robolectric")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tech.mmarca.openwatch.core.ui"
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
    // Public signatures carry Compose's types and the models the rows and formatters take.
    val composeBom = platform(libs.androidx.compose.bom)
    api(composeBom)
    api(libs.androidx.compose.ui)
    api(libs.androidx.compose.ui.graphics)
    api(libs.androidx.compose.material3)
    api(project(":core:model"))
    // Compose's own lifecycle artifacts at the version the app ships. Without it, a module that
    // takes these components and no lifecycle library of its own (the link host's notifier)
    // compiles against the older lifecycle Compose names, which no other module resolves.
    api(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    // The permission launchers and the view model store a screen's app bar is declared against.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.core.ktx)
    // The Health Connect permission launcher and the way into Health Connect's settings.
    implementation(project(":core:health"))
    // The launcher reaches the gateway through a Hilt entry point.
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
