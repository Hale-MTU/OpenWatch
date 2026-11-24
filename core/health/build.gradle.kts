// Health Connect as the app reaches it: HealthConnectGateway, the one class that holds the
// client (the granted set, the strict reads, inserts and deletes by client id, the page
// reader), the record sink the devices write through (ImportedRecordRepository) and the
// activity write path (ActivityRecordWriter, ActivityWriteRepository), the sync switch's gate,
// availability, the intents into Health Connect's own screens, and the data catalog the app
// shows and HealthPermissionsTest holds the manifests to. In the app's packages
// (`healthconnect`, `data/repository`). It implements :core:health-api's contracts; the screens
// and the data layer use it, the devices never do.
plugins {
    id("openwatch.android.library")
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "tech.mmarca.openwatch.core.health"
    // SDK levels, the ci and nightly build types and test sizing: openwatch.android.library.

    defaultConfig {
        minSdk = 26
    }

    lint {
        // As the app's: Weblate translates the strings, a language at a time.
        disable += setOf("MissingTranslation")
    }
}

dependencies {
    // Public signatures carry Health Connect's records and the contracts the gateway and the
    // sink implement (HealthConnectReads, HealthRecordStore), the models of an activity write
    // and the activity write contract the device import calls.
    api(project(":core:health-api"))
    api(project(":core:model"))
    api(project(":core:devices-api"))
    // The foreground gate, which drops the cached grants on a return to the app.
    implementation(project(":devices:core"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.health.connect.testing)
}
