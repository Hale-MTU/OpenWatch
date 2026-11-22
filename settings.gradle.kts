pluginManagement {
    // The convention plugins the modules apply (openwatch.android.application and the rest).
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OpenWatch"
// A clone without its submodules would otherwise fail deep in the composite build, naming a
// project or a plugin that is missing rather than the step that was.
listOf("openlink", "health-calculations")
    .filterNot { file("$it/settings.gradle.kts").isFile }
    .takeIf { it.isNotEmpty() }
    ?.let { missing ->
        throw GradleException(
            "The submodules ${missing.joinToString()} are not checked out. " +
                "Run: git submodule update --init --recursive",
        )
    }
// The OpenLink control channel (git submodule at openlink/): OpenWatch hosts it.
includeBuild("openlink")
// The health formulas OpenWatch and OpenVitals share (git submodule at health-calculations/).
includeBuild("health-calculations")
include(":app")
include(":core:model")
include(":core:fit")
include(":wear")
include(":wear:recording-core")
include(":wearlink")
