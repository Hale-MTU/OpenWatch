// The watch app's pure core: what the recorders decide and compute (the recorder plan, the
// sleep minute rows, the sleep hold and its budget, the bedtime window, the step and ascent
// minutes, the night HRV schedule, the workout's tracker and its store over an interface),
// the series the store keeps, the settings' link codec, the retention warning and the tiles'
// day. Plain Kotlin on the JDK, so its tests run without Robolectric. Package names are the
// watch app's; :wear depends on this module and implements its interfaces (WorkoutRows,
// Recorder). Nothing here reads a sensor, a preference or the store.
plugins {
    id("openwatch.jvm.library")
    // Android lint checked these files in :wear; it still does, with the same rules (build-logic/).
    id("com.android.lint")
}

dependencies {
    // Public types of this module reference the link's rows and settings.
    api(project(":wearlink"))
    // The minute features and the ascent credit, shared with the phone.
    implementation("tech.mmarca.healthcalculations:health-calculations")

    testImplementation(libs.junit4)
}
