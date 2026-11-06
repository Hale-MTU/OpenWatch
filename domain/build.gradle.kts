// App-local code with no dependency on the rest of the phone app: the device
// and sleep models, the sleep and walking estimators and the body profile under
// `domain`, and the FIT and geo primitives under `core`. Packages keep their
// names; :app depends on this module.
//
// An Android library rather than plain Kotlin: a handful of files use Health
// Connect record constants and the platform's locale data, and Android lint
// then still checks the module, on its own task beside the app's. The module
// exists for build time: lint and the unit tests run here in parallel with
// the app's, and a change elsewhere leaves this module's results cached.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "tech.mmarca.openwatch.domain"
    compileSdk = 37
    buildToolsVersion = "37.0.0"
    // Same build types as the app, so the app's ci variant takes this module's
    // ci variant and the tests and lint here run on the one compile. A fallback
    // to debug would instead change how a multiplatform dependency resolves,
    // against the app's lockfile.
    testBuildType = "ci"

    defaultConfig {
        minSdk = 26
    }

    buildTypes {
        create("ci") {
            initWith(getByName("debug"))
        }
        create("nightly") {
            initWith(getByName("release"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The Wear OS sleep-minute fixtures, used by this module's tests and by the
    // app's Wear OS sync tests.
    testFixtures {
        enable = true
    }

    lint {
        ignoreTestSources = true
        // As in the app: the full lint already ran on the commit.
        checkReleaseBuilds = false
        // The fixtures do not ship either.
        ignoreTestFixturesSources = true
    }
}

dependencies {
    // Public types of this module reference both.
    api(project(":wearlink"))
    api(libs.health.connect.client)

    testFixturesImplementation(libs.truth)

    testImplementation(libs.junit4)
    testImplementation(libs.truth)
    testImplementation(libs.org.json)
}

tasks.withType<Test>().configureEach {
    // Same sizing as the app's tests: half the cores, every core on a
    // tests-only CI job.
    maxParallelForks = providers.gradleProperty("openwatch.testForks").orNull?.toInt()
        ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
    maxHeapSize = "1g"
}
