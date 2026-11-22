import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.process.CommandLineArgumentProvider
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** What every OpenWatch module shares, Android or not: its coordinates and its lockfile. */
internal fun Project.configureBasics() {
    group = providers.gradleProperty("openWatchGroup").orElse("tech.mmarca.openwatch").get()
    version = providers.gradleProperty("openWatchArtifactVersion")
        .orElse(providers.environmentVariable("OPENWATCH_ARTIFACT_VERSION"))
        .orElse(providers.gradleProperty("openwatch.versionName").map { "$it-SNAPSHOT" })
        .get()
    dependencyLocking { lockAllConfigurations() }
}

/** The Android levels every module compiles at. */
internal fun Project.configureAndroid(android: CommonExtension) {
    android.compileSdk = 37
    android.buildToolsVersion = "37.0.0"
    android.compileOptions.sourceCompatibility = JavaVersion.VERSION_17
    android.compileOptions.targetCompatibility = JavaVersion.VERSION_17
    android.lint.apply {
        // No lint vital on release-type builds: the gate's lint job already ran the full lint
        // on the commit, and lint vital ran beside R8 in the signing build (five minutes, 2026-10).
        checkReleaseBuilds = false
        // A new warning fails the gate. What was there when this was switched on is recorded
        // in each module's lint-baseline.xml and is worked off from there, never added to.
        warningsAsErrors = true
        baseline = file("lint-baseline.xml")
        // These change with the calendar, not with the code: a new library or SDK release would
        // fail a commit that touched nothing. Upgrades are their own decision.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        // Weblate writes the translations. A plural shape or a word in one of them is reported,
        // not a gate failure; scripts/verify-translations.py checks what can break at runtime.
        informational += setOf("MissingQuantity", "UnusedQuantity", "Typos")
    }
}

/**
 * Unit tests spread over a few JVMs: half the cores, at most four, since each fork is about
 * a gigabyte and lint and the compiler run beside them. A tests-only CI job sets
 * -Popenwatch.testForks to use every core.
 */
internal fun Project.configureTestSizing() {
    tasks.withType<Test>().configureEach {
        maxParallelForks = providers.gradleProperty("openwatch.testForks").orNull?.toInt()
            ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)
        maxHeapSize = "1g"
    }
}

/**
 * Robolectric's Android runtime as a dependency Gradle resolves. Left to itself, Robolectric
 * downloads the jar (150 MB) from Maven Central in every test fork, uncached and unchecked, and
 * one failed download there failed every Robolectric test of a CI run (2026-11-22). Resolved
 * here, it is cached with the rest and checked against gradle/verification-metadata.xml, and the
 * tests run Robolectric offline from a copy in the build directory. The tests pin SDK 34; a test
 * on another level needs that level's jar in the catalog too.
 */
internal fun Project.configureRobolectricRuntime() {
    val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
    val runtime = configurations.create("robolectricRuntime") {
        isCanBeConsumed = false
        isTransitive = false
    }
    dependencies.add(runtime.name, catalog.findLibrary("robolectric-android-all").get())
    val runtimeDir = layout.buildDirectory.dir("robolectric-runtime")
    val syncRuntime = tasks.register<Sync>("syncRobolectricRuntime") {
        from(runtime)
        into(runtimeDir)
    }
    tasks.withType<Test>().configureEach {
        dependsOn(syncRuntime)
        inputs.files(runtime).withPropertyName("robolectricRuntime").withPathSensitivity(PathSensitivity.NONE)
        systemProperty("robolectric.offline", "true")
        // An argument provider, not a system property: the absolute path must not become an
        // input, or a cached result from another checkout would never match.
        jvmArgumentProviders.add(
            CommandLineArgumentProvider { listOf("-Drobolectric.dependency.dir=${runtimeDir.get().asFile.absolutePath}") },
        )
    }
}

/**
 * The phone and the watch app: one applicationId, one key. Signing comes from the
 * environment the release pipeline sets; without it the release builds are unsigned.
 * Debug, release (R8 with resource shrinking) and nightly (a release with its own suffix).
 * What differs between the two apps (version codes, the ci type, the diagnostics flag)
 * stays in their build files.
 */
class OpenWatchAndroidApplicationPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.application")
        configureBasics()
        configureRobolectricRuntime()
        extensions.configure<ApplicationExtension> {
            configureAndroid(this)
            val signing = ReleaseSigning.fromEnvironment()
            signingConfigs {
                if (signing != null) {
                    create("release") {
                        storeFile = file(signing.storeFile)
                        storePassword = signing.storePassword
                        keyAlias = signing.keyAlias
                        keyPassword = signing.keyPassword
                    }
                }
            }
            buildTypes {
                getByName("debug") {
                    applicationIdSuffix = ".debug"
                    versionNameSuffix = "-debug"
                    if (signing != null && System.getenv("OPENWATCH_SIGN_DEBUG_WITH_RELEASE_KEY") == "true") {
                        signingConfig = signingConfigs.getByName("release")
                    }
                    // The release pipeline publishes a debug APK: not debuggable, since it is
                    // public, and not run through R8 either (it was until 2026-10: six minutes
                    // of every nightly, and nothing needed the shape).
                    if (System.getenv("OPENWATCH_PUBLISHED_DEBUG_APK") == "true") {
                        isDebuggable = false
                    }
                }
                getByName("release") {
                    if (signing != null) signingConfig = signingConfigs.getByName("release")
                    isMinifyEnabled = true
                    isShrinkResources = true
                    proguardFiles(
                        getDefaultProguardFile("proguard-android-optimize.txt"),
                        "proguard-rules.pro",
                    )
                }
                create("nightly") {
                    initWith(getByName("release"))
                    matchingFallbacks += listOf("release")
                }
            }
            dependenciesInfo {
                // F-Droid: no Google-encrypted dependency blob in the APK or the bundle.
                includeInApk = false
                includeInBundle = false
            }
        }
    }
}

/**
 * An Android library of OpenWatch: the app's build types, so the app's ci variant takes
 * this module's ci variant and its tests and lint run on that one compile.
 */
class OpenWatchAndroidLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        configureBasics()
        extensions.configure<LibraryExtension> {
            configureAndroid(this)
            testBuildType = "ci"
            buildTypes {
                create("ci") { initWith(getByName("debug")) }
                create("nightly") { initWith(getByName("release")) }
            }
            lint.ignoreTestSources = true
        }
        configureTestSizing()
    }
}

/** A pure-JVM module, compiled for Java 17 like the apps. */
class OpenWatchJvmLibraryPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.jvm")
        configureBasics()
        extensions.configure<KotlinJvmProjectExtension> { jvmToolchain(17) }
    }
}

/** The release key, from the environment the release pipeline sets. Null when any part is missing. */
internal data class ReleaseSigning(
    val storeFile: String,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String,
) {
    companion object {
        fun fromEnvironment(): ReleaseSigning? {
            val storeFile = System.getenv("OPENWATCH_RELEASE_STORE_FILE")
            val storePassword = System.getenv("OPENWATCH_RELEASE_STORE_PASSWORD")
            val keyAlias = System.getenv("OPENWATCH_RELEASE_KEY_ALIAS")
            // A PKCS#12 store has one password for the store and the key.
            val pkcs12 = storeFile?.lowercase()?.let { it.endsWith(".p12") || it.endsWith(".pfx") || it.endsWith(".pkcs12") } == true
            val keyPassword = if (pkcs12) storePassword else System.getenv("OPENWATCH_RELEASE_KEY_PASSWORD")
            if (listOf(storeFile, storePassword, keyAlias, keyPassword).any { it.isNullOrBlank() }) return null
            return ReleaseSigning(storeFile!!, storePassword!!, keyAlias!!, keyPassword!!)
        }
    }
}
