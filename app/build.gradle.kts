import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

plugins {
    id("openwatch.android.application")
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
    // @Serializable navigation routes.
    alias(libs.plugins.kotlin.serialization)
}

// Signing, R8 and the shared build types: openwatch.android.application (build-logic/).
val apkAbiFilters = System.getenv("OPENWATCH_APK_ABI_FILTERS")
    ?.split(',')
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?.toSet()
    ?: emptySet()
val versionCodeOverride = providers.environmentVariable("OPENWATCH_VERSION_CODE")
    .map { it.toInt() }
val versionNameOverride = providers.environmentVariable("OPENWATCH_VERSION_NAME")
val nightlyVersionNameSuffix = versionNameOverride
    .map { "" }
    .orElse("-nightly")
// versionCode is a monotonic release counter, independent of versionName.
// The phone app counts up from 1; the watch app owns 2_000_000_001 and up
// (wear/build.gradle.kts), since both ship under one applicationId. The
// release value lives in gradle.properties, which both modules read.
val baseVersionCode = providers.gradleProperty("openwatch.versionCode").map { it.toInt() }
val baseVersionName = providers.gradleProperty("openwatch.versionName")
val phoneVersionCode = versionCodeOverride.orElse(baseVersionCode).get()
require(phoneVersionCode in 1..99_999_999) {
    "versionCode $phoneVersionCode must leave room for the watch's 2_000_000_000 + versionCode."
}
android {
    namespace = "tech.mmarca.openwatch"
    testBuildType = "ci"

    defaultConfig {
        applicationId = "tech.mmarca.openwatch"
        minSdk = 26
        targetSdk = 36
        // The instrumented tests are JUnit 4; without this runner none of them would run.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = phoneVersionCode
        versionName = versionNameOverride.orElse(baseVersionName).get()
        buildConfigField("boolean", "OPENWATCH_DIAGNOSTICS", "false")
        if (apkAbiFilters.isNotEmpty()) {
            ndk {
                abiFilters.addAll(apkAbiFilters)
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "OPENWATCH_DIAGNOSTICS", "true")
        }

        create("ci") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".ci"
            versionNameSuffix = "-ci"
        }

        getByName("nightly") {
            versionNameSuffix = nightlyVersionNameSuffix.get()
            buildConfigField("boolean", "OPENWATCH_DIAGNOSTICS", "true")
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Robolectric reads the merged manifest and resources for the Hilt graph test.
    testOptions.unitTests.isIncludeAndroidResources = true

    lint {
        // Tests do not ship. Analysing them cost as much as the app itself.
        ignoreTestSources = true
        disable += setOf(
            // Partial Weblate languages are allowed once scripts/verify-translations.py
            // confirms at least 70% coverage and placeholder safety.
            "MissingTranslation",
        )
    }
}

// Nothing runs the minified build before it ships, so every R8 task is followed by a check
// that it kept what is reached by name: the manifest components, and the members through which
// kotlinx-serialization finds a navigation route's serializer. A failure here fails the build.
androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        // AGP's public artifacts, not build paths: AGP 9.4 stopped writing outputs/mapping.
        val mapping = variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
        val manifest = variant.artifacts.get(SingleArtifact.MERGED_MANIFEST)
        val script = rootProject.file("scripts/verify-r8-keeps.py")
        val sources = file("src/main/kotlin")
        val verifyR8Keeps = tasks.register<Exec>("verify${variantName}R8Keeps") {
            group = "verification"
            description = "Checks that R8 kept what the ${variant.name} build reaches by name."
            workingDir(rootProject.projectDir)
            inputs.file(mapping)
            inputs.file(manifest)
            executable("python3")
            argumentProviders.add(
                CommandLineArgumentProvider {
                    listOf(
                        script.path,
                        "--mapping", mapping.get().asFile.path,
                        "--manifest", manifest.get().asFile.path,
                        "--sources", sources.path,
                    )
                },
            )
        }
        tasks.matching { it.name == "minify${variantName}WithR8" }.configureEach {
            finalizedBy(verifyR8Keeps)
        }
    }
}

dependencies {
    // The OpenLink host: what OpenWatch offers through it, with the library (the openlink/
    // submodule, a composite build) as its api; the trust store and the binder are built in
    // di/LinkModule.
    implementation(project(":link-host"))
    // The apps that use OpenWatch over OpenLink.
    implementation(project(":feature:connectedapps"))
    // The Health Connect setup screen and the health data list.
    implementation(project(":feature:healthconnect"))
    // The pure models, which also bring health-calculations, and the FIT codec.
    implementation(project(":core:model"))
    implementation(project(":core:fit"))
    // The device contracts: families, features, the sync port, pairing, presence.
    implementation(project(":core:devices-api"))
    // The devices' Health Connect contracts: the record sink, its write helpers, the strict reads.
    implementation(project(":core:health-api"))
    // Health Connect as the app reaches it: the gateway, the record sink, the activity writes, the catalog.
    implementation(project(":core:health"))
    // The theme, the shared components, the formatters and ScreenError, the OS permission checks.
    implementation(project(":core:ui"))
    // Room, the registry, the per-device state and the preferences. The database is built here
    // (di/AppModule); a family's own table contract is implemented here over its DAO.
    implementation(project(":core:data"))
    // What every device family shares on Android: the radio lease, the GATT link and the BLE
    // scanner, companion pairing, the sync controller and its schedule.
    implementation(project(":devices:core"))
    // The Garmin watches, which bind themselves (GarminModule); the protocol comes with them.
    implementation(project(":devices:garmin"))
    // The Wear OS watch's phone side, which binds itself (WearOsModule).
    implementation(project(":devices:wearos"))
    // The Xiaomi S400 scale, which binds itself (XiaomiScaleModule); its codec comes with it.
    implementation(project(":devices:xiaomi"))
    // The devices of the Bluetooth standard health profiles, which bind themselves (BleStandardModule).
    implementation(project(":devices:ble-standard"))
    implementation(project(":wearlink"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)

    // Compose
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // Instrumentation runs against the `ci` variant, not `debug`. ui-test-manifest
    // is what declares the ComponentActivity that createComposeRule launches into,
    // so without it on this variant every Compose test fails identically at
    // startup with "Unable to resolve activity for MAIN/LAUNCHER" — before any
    // assertion runs, and regardless of what the test does.
    "ciImplementation"(libs.androidx.compose.ui.test.manifest)

    // Activity + Navigation
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Lifecycle + ViewModel
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Background work
    implementation(libs.androidx.work.runtime.ktx)

    // The database is built here; its entities, DAOs and migrations are :core:data's.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)

    // Health Connect
    implementation(libs.health.connect.client)

    // Dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Testing
    // The shared fakes of the device contracts: a sync port, a pairing, a family.
    testImplementation(testFixtures(project(":core:devices-api")))
    // The FIT test bytes the FIT codec's tests build their files with.
    testImplementation(testFixtures(project(":devices:garmin:protocol")))
    // Health Connect's writes in memory, for the write helpers' callers.
    testImplementation(testFixtures(project(":core:health-api")))
    // SharedPreferences in memory and a SecretSeal on a software key, shared with the device modules.
    testImplementation(testFixtures(project(":devices:core")))
    // The main dispatcher swapped for the tests' (MainDispatcherRule).
    testImplementation(testFixtures(project(":core:model")))
    // The registry and the body profile in memory.
    testImplementation(testFixtures(project(":core:data")))
    // The weigh-in table in memory, for the Scales screens.
    testImplementation(testFixtures(project(":devices:xiaomi")))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.health.connect.testing)
    testImplementation(libs.truth)
    // Real org.json for local tests (the android.jar copy is a throwing stub).
    testImplementation(libs.org.json)
    // The Hilt graph test builds the real graph on the JVM.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.android.compiler)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.espresso.core)
}

tasks.withType<Test>().configureEach {
    jvmArgs("-XX:+EnableDynamicAgentLoading")
    // Test classes spread over a few JVMs. Half the cores, at most four: each fork
    // is about a gigabyte, and lint and the Kotlin compiler run at the same time.
    // A CI job that runs only the tests sets -Popenwatch.testForks to use every core.
    maxParallelForks = providers.gradleProperty("openwatch.testForks").orNull?.toInt()
        ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    maxHeapSize = "1g"
    // The memory budget above assumes one test task at a time. The pure modules'
    // tests take seconds; they go first rather than beside these.
    mustRunAfter(
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
        ":link-host:testCiUnitTest",
        ":feature:connectedapps:testCiUnitTest",
        ":feature:healthconnect:testCiUnitTest",
    )
    // The architecture tests read sources and documents as plain files: this module's
    // and the others' code, both manifests, the strings, the privacy documents. None
    // of that is on the test classpath, so it is declared here; otherwise a change to
    // one of them alone is answered with a cached pass.
    val repository = rootProject.layout.projectDirectory
    mapOf(
        "scannedAppMain" to "app/src/main",
        "scannedCoreModelMain" to "core/model/src/main",
        "scannedCoreFitMain" to "core/fit/src/main",
        "scannedCoreDevicesApiMain" to "core/devices-api/src/main",
        "scannedCoreHealthApiMain" to "core/health-api/src/main",
        "scannedCoreHealthMain" to "core/health/src/main",
        "scannedCoreUiMain" to "core/ui/src/main",
        "scannedCoreDataMain" to "core/data/src/main",
        "scannedDevicesCoreMain" to "devices/core/src/main",
        "scannedGarminMain" to "devices/garmin/src/main",
        "scannedGarminProtocolMain" to "devices/garmin/protocol/src/main",
        "scannedWearOsMain" to "devices/wearos/src/main",
        "scannedXiaomiMain" to "devices/xiaomi/src/main",
        "scannedXiaomiProtocolMain" to "devices/xiaomi/protocol/src/main",
        "scannedBleStandardMain" to "devices/ble-standard/src/main",
        "scannedBleStandardProtocolMain" to "devices/ble-standard/protocol/src/main",
        "scannedLinkHostMain" to "link-host/src/main",
        "scannedFeatureConnectedappsMain" to "feature/connectedapps/src/main",
        "scannedFeatureHealthconnectMain" to "feature/healthconnect/src/main",
        "scannedWearMain" to "wear/src/main",
        "scannedWearRecordingCoreMain" to "wear/recording-core/src/main",
        "scannedWearlinkMain" to "wearlink/src/main",
    ).forEach { (name, path) ->
        inputs.dir(repository.dir(path)).withPropertyName(name).withPathSensitivity(PathSensitivity.RELATIVE)
    }
    mapOf(
        // DevicesLayeringTest reads which modules a device module depends on.
        "scannedGarminBuild" to "devices/garmin/build.gradle.kts",
        "scannedWearOsBuild" to "devices/wearos/build.gradle.kts",
        "scannedXiaomiBuild" to "devices/xiaomi/build.gradle.kts",
        "scannedBleStandardBuild" to "devices/ble-standard/build.gradle.kts",
        // ...and which device modules a screen module depends on.
        "scannedFeatureHealthconnectBuild" to "feature/healthconnect/build.gradle.kts",
        "scannedFeatureConnectedappsBuild" to "feature/connectedapps/build.gradle.kts",
        "scannedPrivacyPolicy" to "PRIVACY.md",
        "scannedPermissionsDoc" to "docs/app/permissions.md",
    ).forEach { (name, path) ->
        inputs.file(repository.file(path)).withPropertyName(name).withPathSensitivity(PathSensitivity.RELATIVE)
    }
}

// The architecture tests alone. They read the watch app's and the link's sources too, and a
// change to those alone runs only the watch gate, so that gate runs this.
tasks.register<Test>("architectureTest") {
    description = "The architecture and ratchet tests of :app, on the ci variant."
    group = "verification"
    val unitTests = tasks.named<Test>("testCiUnitTest").get()
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter.includeTestsMatching("tech.mmarca.openwatch.architecture.*")
}
