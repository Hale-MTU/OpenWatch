// What the phone keeps: the Room database (data/local: the device registry, the per-device
// state, and the device tables, all under the database name, version, migrations and schemas
// the app has always had), the registry over it (BleDeviceRepository), the per-device state
// store, the app's preferences (OpenWatchPreferences) and the body profile with Health
// Connect's weight and height on top. In the app's packages. It depends on no device family:
// a family's own table or preference contract is the family's, and the app implements it over
// what this module holds. The Hilt bindings, the database among them, are the app's.
plugins {
    id("openwatch.android.library")
    id("openwatch.robolectric")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

// Room writes the schema of each database version here. The files are committed: a migration
// can only be tested against a version whose schema was kept.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "tech.mmarca.openwatch.core.data"
    // SDK levels, the ci and nightly build types and test sizing: openwatch.android.library.

    defaultConfig {
        minSdk = 26
    }

    // The registry's and the stores' fakes, for the screens' tests.
    testFixtures {
        enable = true
    }

    testOptions.unitTests.isIncludeAndroidResources = true
}

// The exported Room schemas, for the migration test. Robolectric reads the tested variant's
// merged assets, not the test source set's, so they go into the ci variant, which only the tests
// use and nothing ships.
androidComponents {
    onVariants(selector().withBuildType("ci")) { variant ->
        variant.sources.assets?.addStaticSourceDirectory("schemas")
    }
}

dependencies {
    // Public signatures carry the models, the registry and state contracts the repositories
    // implement, and the sync switch the preferences implement.
    api(project(":core:model"))
    api(project(":core:devices-api"))
    api(project(":core:health"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // FakeDeviceDao's registry reads the legacy JSON from SharedPreferences in memory.
    testFixturesImplementation(testFixtures(project(":devices:core")))

    testImplementation(testFixtures(project(":devices:core")))
    // The registry's tests read a stored row as the Garmin family names it.
    testImplementation(project(":devices:garmin:protocol"))
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for the legacy registry import (the android.jar copy is a throwing stub).
    testImplementation(libs.org.json)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
