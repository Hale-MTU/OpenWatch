package tech.mmarca.openwatch.architecture

import java.io.File

/**
 * The modules the phone app is built from besides itself, by directory, each mapped to its
 * main source root as seen from `:app`. They keep the app's package names, so a file below one
 * of these roots reads as the app's file of the same package, and the ratchets that walk the
 * app walk them too. A new module goes here and into the scanned inputs in app/build.gradle.kts.
 */
internal val LibraryModuleRoots: Map<String, String> = listOf(
    "core/model",
    "core/fit",
    "core/devices-api",
    "core/health-api",
    "devices/core",
    "devices/garmin/protocol",
    "devices/wearos",
    "devices/xiaomi",
    "devices/xiaomi/protocol",
).associateWith { module -> "../$module/src/main/kotlin/tech/mmarca/openwatch" }

/** The root of [LibraryModuleRoots] this file is under, or null for a file of another module. */
internal fun File.libraryModuleRoot(): String? =
    LibraryModuleRoots.values.firstOrNull { root -> invariantSeparatorsPath.startsWith("$root/") }
