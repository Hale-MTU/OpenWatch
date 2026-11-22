// The convention plugins OpenWatch's modules share: SDK levels, Java level, build types,
// signing, lint, locking. An included build, so a change here rebuilds only what uses it.
// The kotlin-dsl plugin's own dependencies are Kotlin artifacts: from Maven Central first, so a
// build does not hang on the plugin portal's proxy of it (a CI run failed on that, 2026-11-22).
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
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
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
