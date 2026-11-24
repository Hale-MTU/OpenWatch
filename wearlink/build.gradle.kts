// The phone-to-watch link, shared by :app and :wear: the line protocol, its
// framing, the trust token, and a client and server that work over plain
// streams, and what a workout comes to (WorkoutMetrics), so the watch's
// summary and what the phone writes are one number. Pure Kotlin on the JDK
// and health-calculations (a composite build, plain Kotlin with no dependency
// either), so a loopback test runs both ends in one JVM and neither app build
// depends on the other.
plugins {
    id("openwatch.jvm.library")
}

dependencies {
    // The workout figures are health-calculations' formulas, and WorkoutMetrics takes its body profile.
    api("tech.mmarca.healthcalculations:health-calculations")

    testImplementation(libs.junit4)
}
