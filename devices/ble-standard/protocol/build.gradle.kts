// The Bluetooth SIG's standard health profiles as bytes, free of Android and of the app: the
// measurement characteristics as the specifications lay them out, and the payloads the family
// keeps a measurement in until Health Connect has it. Package names are the app's
// (`devices/blestandard`). The link, the pairing and the Health Connect mapping are
// :devices:ble-standard's, which depends on this module.
plugins {
    id("openwatch.jvm.library")
    // The same Android lint rules as the other protocol modules (build-logic/).
    id("com.android.lint")
}

// Garmin's and Xiaomi's modules are named `protocol` too; the family in the group keeps the
// module identities apart.
group = "${group}.devices.blestandard"

kotlin {
    explicitApi()
}

dependencies {
    testImplementation(libs.junit4)
}
