// The Xiaomi S400 scale's codec, free of Android and of the app: MiBeacon v5 framing, the
// AES-CCM decryption under the bind key, the weigh-in object and the scan-filter bytes. Package
// names are the app's (`devices/xiaomi`). The scan, the listening service, Room and the Health
// Connect mapping stay in :app, which depends on this module.
plugins {
    id("openwatch.jvm.library")
    // Android lint checked these files in :app; it still does, with the same rules (build-logic/).
    id("com.android.lint")
}

// Two projects named `protocol` (Garmin's and Xiaomi's) would share one module identity under
// the common group, and Gradle would then resolve one in place of the other without a word.
// The vendor in the group keeps them apart.
group = "${group}.devices.xiaomi"

kotlin {
    explicitApi()
}

dependencies {
    // Public signatures carry the scale reading, the device model and the scan filter.
    api(project(":core:model"))

    testImplementation(libs.junit4)
}
