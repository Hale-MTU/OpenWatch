plugins {
    id("openwatch.android.application")
    alias(libs.plugins.kotlin.compose)
}

// The watch app shares the phone app's applicationId so Play lists it as a
// form factor of OpenWatch, not as a second app. Same rules follow: every
// artifact in one listing needs a unique versionCode, so the watch owns the
// range 2_000_000_000 to 2_099_999_999. The phone counter stays below that.
// Play caps versionCode at 2_100_000_000.
val wearVersionCodeBase = 2_000_000_000
// The release value lives in gradle.properties, shared with app/.
val wearBaseVersionCode = providers.gradleProperty("openwatch.versionCode")
    .map { wearVersionCodeBase + it.toInt() }
val wearBaseVersionName = providers.gradleProperty("openwatch.versionName")

// Signing, R8 and the shared build types: openwatch.android.application (build-logic/).
val wearVersionCodeOverride = providers.environmentVariable("OPENWATCH_WEAR_VERSION_CODE")
    .map { it.toInt() }
val wearVersionNameOverride = providers.environmentVariable("OPENWATCH_WEAR_VERSION_NAME")
// Same rule as the phone app: a nightly carries "-nightly" unless the
// release pipeline supplied an explicit version name.
val wearNightlyVersionNameSuffix = wearVersionNameOverride
    .map { "" }
    .orElse("-nightly")
val wearVersionCode = wearVersionCodeOverride.orElse(wearBaseVersionCode).get()
require(wearVersionCode in (wearVersionCodeBase + 1)..2_099_999_999) {
    "OPENWATCH_WEAR_VERSION_CODE $wearVersionCode is outside the watch range " +
        "${wearVersionCodeBase + 1}..2099999999. Phone codes live below it."
}

android {
    // Kotlin namespace stays under .wear; only the applicationId is shared.
    namespace = "tech.mmarca.openwatch.wear"

    defaultConfig {
        applicationId = "tech.mmarca.openwatch"
        minSdk = 30
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = 36
        versionCode = wearVersionCode
        versionName = wearVersionNameOverride.orElse(wearBaseVersionName).get()
    }

    buildTypes {
        // The debug suffix is the phone debug build's, so a debug watch pairs with a debug phone.
        getByName("nightly") {
            versionNameSuffix = wearNightlyVersionNameSuffix.get()
        }
    }

    buildFeatures {
        compose = true
    }

    lint {
        // As on the phone: a string added between releases ships in English until the release
        // translates it, and scripts/verify-translations.py checks what can break at run time.
        disable += "MissingTranslation"
    }

    // The store tests run twice: on the JVM under Robolectric, in every watch gate, and on a
    // watch as instrumented tests, against the watch's own SQLite.
    sourceSets.getByName("test").kotlin.directories.add("src/sharedTest/kotlin")
    sourceSets.getByName("androidTest").kotlin.directories.add("src/sharedTest/kotlin")
}

dependencies {
    implementation(project(":wearlink"))
    // The recorders' pure decisions and computations, tested on the plain JVM.
    implementation(project(":wear:recording-core"))
    // HRV, respiratory rate and the minute features from the raw sensors, shared with the phone.
    implementation("tech.mmarca.healthcalculations:health-calculations")
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    // Same icon set as the phone app, so a metric keeps its glyph on the watch.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.wear.compose.material3)
    implementation(libs.androidx.wear.compose.navigation)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    // The watch face pill of a running workout.
    implementation(libs.androidx.wear.ongoing)
    // The metric tiles: Material 3 tile layouts, no Play Services.
    implementation(libs.androidx.wear.tiles)
    implementation(libs.androidx.wear.protolayout)
    implementation(libs.androidx.wear.protolayout.material3)
    // A suspend function as the ListenableFuture a TileService returns.
    implementation(libs.androidx.concurrent.futures.ktx)
    // The heart rate and steps complications: a watch face's slot, read like the tiles. The
    // library declares androidx.preference, which brings AppCompat and Fragment, yet none of its
    // classes or resources refers to it (checked in 1.3.0): it is left out. Check again on an update.
    implementation(libs.androidx.wear.watchface.complications.data.source.ktx) {
        exclude(group = "androidx.preference", module = "preference")
    }
    testImplementation(libs.junit4)
    // The shared store tests, on the JVM.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Real org.json for local tests (the android.jar copy is a throwing stub): the feature vector is JSON.
    testImplementation(libs.org.json)
    // The metric store against real SQLite, on a watch or emulator; CI compiles it.
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
