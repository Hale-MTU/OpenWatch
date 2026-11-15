// The phone-to-watch link, shared by :app and :wear: the line protocol, its
// framing, the trust token, and a client and server that work over plain
// streams. Pure Kotlin on the JDK only, so a loopback test runs both ends in
// one JVM and neither app build depends on the other. health-calculations is
// pure Kotlin too; the workout's figures come from it.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api("tech.mmarca.healthcalculations:health-calculations")
    testImplementation(libs.junit4)
}
