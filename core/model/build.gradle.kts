// The phone app's pure models: the device, sleep-minute, scale and activity models under
// `domain/model`, the unit and theme preferences under `domain/preferences`, and the
// coordinate parsing under `core/geo`. Plain Kotlin on the JDK, with the app's package
// names. The calculations are health-calculations', shared with OpenVitals.
plugins {
    id("openwatch.jvm.library")
    // Android lint checked these files in :domain; it still does, with the same rules (build-logic/).
    id("com.android.lint")
}

dependencies {
    // The activity models carry health-calculations' route, lap and pause types in their
    // public signatures (the health-calculations/ submodule, a composite build).
    api("tech.mmarca.healthcalculations:health-calculations")

    testImplementation(libs.junit4)
}
