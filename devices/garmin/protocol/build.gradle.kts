// The Garmin protocol, free of Android and of the app: the byte primitives, COBS and the
// CRC, GFDI framing and the message vocabulary, protobuf, the multi-link transport over an
// abstract byte pipe, the session state machine and its responders, file sync, the uploads
// the watch takes (alarms, locations, GPS ephemeris) and the FIT wellness decoder. Package
// names are the app's (`devices/garmin`, `devices/garmin/wellness`). The GATT client, the
// stores on SharedPreferences, the Health Connect mapping and the Hilt-bound owners stay in
// :app, which depends on this module.
plugins {
    id("openwatch.jvm.library")
    // Android lint checked these files in :app; it still does, with the same rules (build-logic/).
    id("com.android.lint")
    // The FIT test bytes, shared with the app's tests of the wellness import.
    `java-test-fixtures`
}

// Two projects named `protocol` (Garmin's and Xiaomi's) would share one module identity under
// the common group; the vendor in the group keeps them apart.
group = "${group}.devices.garmin"

kotlin {
    explicitApi()
}

dependencies {
    // Public signatures carry the device model (BleSensorDevice), the platform sources'
    // values (WeatherSnapshot, NotificationMsg) and coroutine types.
    api(project(":core:model"))
    api(libs.kotlinx.coroutines.core)
    // The FIT codec the wellness decoder and the uploads use; no FIT type is in a signature.
    implementation(project(":core:fit"))
    // The watch's HTTP proxy answers in JSON. Android ships org.json, so the app packages none:
    // this compiles against the reference build only. Use the calls Android's copy has, no
    // float, Collection or Map overloads.
    compileOnly(libs.org.json)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
}
