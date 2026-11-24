// The FIT container codec: the decoder, the encoder, the CRC and the 16-bit float. Plain
// Kotlin on the JDK, package `core.fit`. What a record means stays with the code that reads
// it (the Garmin wellness import, the activity import).
plugins {
    id("openwatch.jvm.library")
    // Android lint checked these files in :domain; it still does, with the same rules (build-logic/).
    id("com.android.lint")
}

dependencies {
    testImplementation(libs.junit4)
}
