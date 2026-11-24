// How the devices talk to Health Connect, as contracts: the record sink (HealthRecordStore)
// and the write helpers every device writes with (ImportedRecordWrites: outcomes per record,
// batches of at most MaxRecordsPerInsert, a refusal diagnosed by reading the stored ids), the
// strict reads (HealthConnectReads), the paused-sync exception and the rate-limit check. In
// the app's packages (`data/repository`, `healthconnect`). The implementations, the gateway
// that holds the client and ImportedRecordRepository, stay in :app.
//
// An Android library, not plain Kotlin: Health Connect's records are an Android library's
// types, and they are the sink's signatures. The rate-limit check reads the platform's
// HealthConnectException.
plugins {
    id("openwatch.android.library")
}

android {
    namespace = "tech.mmarca.openwatch.core.healthapi"
    // SDK levels, the ci and nightly build types and test sizing: openwatch.android.library.

    defaultConfig {
        minSdk = 26
    }

    // FakeHealthRecordStore, for the app's tests of the devices' writes.
    testFixtures {
        enable = true
    }
}

dependencies {
    // Record, its metadata and HealthPermission are in the public signatures.
    api(libs.health.connect.client)
    // A refusal is told from a cancellation, which is rethrown.
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
