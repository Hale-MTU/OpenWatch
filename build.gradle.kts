import org.gradle.buildconfiguration.tasks.UpdateDaemonJvm
import org.gradle.jvm.toolchain.JavaLanguageVersion

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// The plugins' own classpath (AGP, Kotlin, Hilt, KSP and what they pull in) is locked too, in
// buildscript-gradle.lockfile: a plugin's transitive dependency cannot move on its own.
buildscript {
    configurations.classpath {
        resolutionStrategy.activateDependencyLocking()
    }
}

// Each module's coordinates and lockfile come from its convention plugin (build-logic/).
dependencyLocking {
    lockAllConfigurations()
}

tasks.named<UpdateDaemonJvm>("updateDaemonJvm") {
    // AGP 9.1 defaults to JDK 17; keep daemon generation aligned with that,
    // and avoid pinning a specific vendor for CI portability.
    languageVersion = JavaLanguageVersion.of(17)
}

// The device is named, never guessed: with ANDROID_SERIAL unset this does nothing, so it cannot
// land on whatever phone or watch adb happens to see. CI sets it to the emulator it boots
// (.github/workflows/android-test.yml).
val hasAndroidSerial = providers.environmentVariable("ANDROID_SERIAL").isPresent

// The phone side's instrumented tests; a module that gains some adds its connected task here,
// and CI's emulator job runs them with it.
tasks.register("verifyAndroidTest") {
    group = "verification"
    description = "Runs the phone app's instrumented tests on the device ANDROID_SERIAL names."
    enabled = hasAndroidSerial
    if (enabled) {
        dependsOn(":app:connectedCiAndroidTest")
    }
}

tasks.register("verifyCiUnitTest") {
    group = "verification"
    description = "Runs the phone app unit tests against the CI build type, and the library modules' and the link's."
    dependsOn(
        ":wearlink:test",
        ":core:model:test",
        ":core:fit:test",
        ":core:devices-api:test",
        ":core:health-api:testCiUnitTest",
        ":core:health:testCiUnitTest",
        ":core:ui:testCiUnitTest",
        ":core:data:testCiUnitTest",
        ":devices:core:testCiUnitTest",
        ":devices:garmin:testCiUnitTest",
        ":devices:garmin:protocol:test",
        ":devices:wearos:testCiUnitTest",
        ":devices:xiaomi:testCiUnitTest",
        ":devices:xiaomi:protocol:test",
        ":devices:ble-standard:testCiUnitTest",
        ":devices:ble-standard:protocol:test",
        ":feature:healthconnect:testCiUnitTest",
        ":feature:connectedapps:testCiUnitTest",
        ":feature:settings:testCiUnitTest",
        ":feature:home:testCiUnitTest",
        ":feature:device:testCiUnitTest",
        ":link-host:testCiUnitTest",
        ":app:testCiUnitTest",
    )
}

tasks.register<Exec>("verifyTranslations") {
    group = "verification"
    description = "Validates Android translation resources for completeness and placeholder safety."
    commandLine("python3", "scripts/verify-translations.py")
}

tasks.register("verifyCiPreflight") {
    group = "verification"
    description = "Runs the translation, duplicate-class and android-test compile checks for CI."
    dependsOn(
        "verifyTranslations",
        // Not assembleCi: nothing used that APK, and dexing it was a minute and a half.
        // The release pipeline builds and packages the real one after this gate.
        ":app:checkCiDuplicateClasses",
        ":app:compileCiAndroidTestKotlin",
    )
}

// Lint is half of the gate's task time, so CI gives it a job of its own beside the tests
// (.github/workflows/verify.yml) rather than a place in verifyCiPreflight.
tasks.register("verifyCiLint") {
    group = "verification"
    description = "Runs phone app and its library modules' lint for CI."
    dependsOn(
        ":app:lintCi",
        ":core:model:lint",
        ":core:fit:lint",
        ":core:devices-api:lint",
        ":core:health-api:lintCi",
        ":core:health:lintCi",
        ":core:ui:lintCi",
        ":core:data:lintCi",
        ":devices:core:lintCi",
        ":devices:garmin:lintCi",
        ":devices:garmin:protocol:lint",
        ":devices:wearos:lintCi",
        ":devices:xiaomi:lintCi",
        ":devices:xiaomi:protocol:lint",
        ":devices:ble-standard:lintCi",
        ":devices:ble-standard:protocol:lint",
        ":feature:healthconnect:lintCi",
        ":feature:connectedapps:lintCi",
        ":feature:settings:lintCi",
        ":feature:home:lintCi",
        ":feature:device:lintCi",
        ":link-host:lintCi",
    )
}

// Phone app only. The wear module has its own gate (verifyWearCi), its own job
// in .github/workflows/verify.yml, so a watch failure never blocks a phone PR.
// The whole gate in one invocation, for a local run and the release build.
tasks.register("verifyCi") {
    group = "verification"
    description = "Runs phone app CI verification without connected-device instrumentation tests."
    dependsOn(
        "verifyCiUnitTest",
        "verifyCiPreflight",
        "verifyCiLint",
    )
}

// Wear OS module only. Same shape as verifyCi: unit tests, lint, build,
// android-test compile. No translation gate yet; the module has no locales.
tasks.register("verifyWearCi") {
    group = "verification"
    description = "Runs Wear OS module unit tests, lint, build, and android-test compile checks for CI."
    dependsOn(
        ":wearlink:test",
        // The watch app's pure core: its tests and Android lint, as when it was part of :wear.
        ":wear:recording-core:test",
        ":wear:recording-core:lint",
        // Unit tests and lint on the ci build type, as the phone's gate; the build and the
        // instrumented tests' compile stay on debug, which a watch's device run installs.
        ":wear:testCiUnitTest",
        ":wear:lintCi",
        ":wear:assembleDebug",
        ":wear:compileDebugAndroidTestKotlin",
        // The ratchets over the watch's sources live in :app.
        ":app:architectureTest",
    )
}

