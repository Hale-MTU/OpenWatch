// The device contracts the screens, OpenLink and the sync controller depend on, and every
// device integration implements: the family and its features, live readings, discovery's
// classifiers and GATT UUIDs, the pairing port, the companion presence observer, the sync
// port with its progress, outcome and failure types, the auto-sync interval, the wake lock
// seam and the per-device record id salt. Plain Kotlin, in the app's package `devices/core`.
// The implementations (the radio lease, pairing on Android, the controller, the schedule)
// stay in :app. Its test fixtures are the fakes the tests share.
plugins {
    id("openwatch.jvm.library")
    // Android lint checked these files in :app; it still does, with the same rules (build-logic/).
    id("com.android.lint")
    `java-test-fixtures`
}

dependencies {
    // Public signatures carry the registry's model (BleSensorDevice, BleDiscoveredDevice,
    // DeviceIntegration) and Flow.
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    // DeviceFamilies is injected; Dagger in :app generates its factory.
    implementation(libs.javax.inject)
    // SyncFailure.Described names a string resource (@StringRes); the annotation is for lint.
    compileOnly(libs.androidx.annotation)

    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
}
