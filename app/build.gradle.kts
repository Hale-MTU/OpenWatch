import com.android.build.api.artifact.SingleArtifact
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

val releaseStoreFilePath = System.getenv("OPENWATCH_RELEASE_STORE_FILE")
val releaseStorePassword = System.getenv("OPENWATCH_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = System.getenv("OPENWATCH_RELEASE_KEY_ALIAS")
val releaseKeyPassword = System.getenv("OPENWATCH_RELEASE_KEY_PASSWORD")
val isPkcs12ReleaseStore = releaseStoreFilePath
    ?.lowercase()
    ?.let { it.endsWith(".p12") || it.endsWith(".pfx") || it.endsWith(".pkcs12") }
    ?: false
val effectiveReleaseKeyPassword = if (isPkcs12ReleaseStore) {
    releaseStorePassword
} else {
    releaseKeyPassword
}

val hasReleaseSigning = listOf(
    releaseStoreFilePath,
    releaseStorePassword,
    releaseKeyAlias,
    effectiveReleaseKeyPassword,
).all { !it.isNullOrBlank() }

val signDebugWithReleaseKey = System.getenv("OPENWATCH_SIGN_DEBUG_WITH_RELEASE_KEY") == "true"
// The release pipeline publishes a debug APK: not debuggable, since it is
// public, but not run through R8 either. It was until 2026-10, to match the
// release build's size and shape; that second R8 pass was six minutes of
// every nightly build, and nothing needed the shape.
val publishedDebugApk = System.getenv("OPENWATCH_PUBLISHED_DEBUG_APK") == "true"
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
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    testBuildType = "ci"

    defaultConfig {
        applicationId = "tech.mmarca.openwatch"
        minSdk = 26
        targetSdk = 36
        versionCode = phoneVersionCode
        versionName = versionNameOverride.orElse(baseVersionName).get()
        buildConfigField("boolean", "OPENWATCH_DIAGNOSTICS", "false")
        if (apkAbiFilters.isNotEmpty()) {
            ndk {
                abiFilters.addAll(apkAbiFilters)
            }
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(checkNotNull(releaseStoreFilePath))
                storePassword = checkNotNull(releaseStorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(effectiveReleaseKeyPassword)
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("boolean", "OPENWATCH_DIAGNOSTICS", "true")
            if (signDebugWithReleaseKey && hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            if (publishedDebugApk) {
                isDebuggable = false
            }
        }

        create("ci") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".ci"
            versionNameSuffix = "-ci"
        }

        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        create("nightly") {
            initWith(getByName("release"))
            versionNameSuffix = nightlyVersionNameSuffix.get()
            buildConfigField("boolean", "OPENWATCH_DIAGNOSTICS", "true")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    lint {
        // Tests do not ship. Analysing them cost as much as the app itself.
        ignoreTestSources = true
        // No lint vital on release-type builds: the pipeline's lint job already
        // ran the full lint on the commit, and the two analyses it added to a
        // nightly build ran beside R8 (five minutes, 2026-10).
        checkReleaseBuilds = false
        disable += setOf(
            "LogNotTimber",
            // Partial Weblate languages are allowed once scripts/verify-translations.py
            // confirms at least 70% coverage and placeholder safety.
            "MissingTranslation",
        )
    }
}

// Nothing runs the minified build before it ships, so every R8 task is followed by a check
// that it kept what is reached by name: the manifest components. A failure here fails the build.
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
    implementation(project(":domain"))
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

    // Health Connect
    implementation(libs.health.connect.client)

    // Dependency injection
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Testing
    // The Wear OS sleep-minute fixtures live with the domain module.
    testImplementation(testFixtures(project(":domain")))
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.health.connect.testing)
    testImplementation(libs.truth)
    // Real org.json for local tests (the android.jar copy is a throwing stub).
    testImplementation(libs.org.json)
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
    // The memory budget above assumes one test task at a time. The domain
    // module's tests take seconds; they go first rather than beside these.
    mustRunAfter(":domain:testCiUnitTest")
}
