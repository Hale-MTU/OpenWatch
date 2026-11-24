package tech.mmarca.openwatch.architecture

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Who wires the device layer. A device family that is a Gradle module of its own
 * (`:devices:<vendor>`) binds itself in its own Hilt module, its family among the rest, so a new
 * device is a new module and a line in the app's build file. Only the app, those modules and the
 * screens' modules (`:feature:*`, which bind their own state holders' collaborators and a
 * family's contributions to the generic screens, as they did in the app) declare Hilt modules:
 * not the device code still in the app (`di/DevicesModule.kt` binds it), not the shared device
 * core, a contract module, a protocol module, a `:core:*` module or the link host. A device
 * module depends on no other device module, on no screen module, nor on the app. The
 * architecture document says so.
 */
class DevicesLayeringTest {

    @Test
    fun `only the app, the device modules and the screens declare Hilt modules, and not in the app's devices`() {
        val misplaced = (mapOf("app" to AppRoot) + LibraryModuleRoots)
            .flatMap { (module, root) -> hiltModulesUnder(root).map { path -> module to path } }
            .filterNot { (module, path) ->
                (module == "app" && !path.startsWith("devices/")) || module in DeviceModules || module in FeatureModules
            }
            .map { (module, path) -> "$module: $path" }

        assertWithMessage(
            "Hilt modules outside the app, the device modules and the screens, or in the app's devices/. A " +
                "family binds itself in its own module; what every family shares is bound in di/DevicesModule.kt.",
        ).that(misplaced).isEmpty()
    }

    @Test
    fun `each device module binds its own family by its persisted name`() {
        val unbound = DeviceModules.filterNot { module ->
            sourcesUnder(LibraryModuleRoots.getValue(module)).any { FamilyBinding.containsMatchIn(it.readText()) }
        }

        assertWithMessage("device modules that bind no DeviceFamily @IntoMap with its @StringKey")
            .that(unbound)
            .isEmpty()
    }

    @Test
    fun `a device module depends on no other device module and not on the app`() {
        val edges = DeviceModules.flatMap { module ->
            val path = ":" + module.replace('/', ':')
            ProjectDependency.findAll(File("../$module/build.gradle.kts").readText())
                .map { it.groupValues[1] }
                .filter { target ->
                    // Its own protocol module and the shared core are the only devices it may name.
                    val anotherFamily = target.startsWith(":devices:") && target != ":devices:core" &&
                        target != path && !target.startsWith("$path:")
                    target == ":app" || target.startsWith(":feature:") || anotherFamily
                }
                .map { target -> "$path -> $target" }
        }

        assertWithMessage("a device module that depends on another family, a screen module or the app").that(edges).isEmpty()
    }

    @Test
    fun `a screen module depends on no device family but the one it is named for`() {
        val edges = FeatureModules.flatMap { module ->
            MainProjectDependency.findAll(File("../$module/build.gradle.kts").readText())
                .map { it.groupValues[1] }
                .filter { target -> target.startsWith(":devices:") && target != ":devices:core" }
                // A family's own screens (`feature/<screens>-<family>`) take that family's module.
                .filterNot { target -> module.endsWith("-" + target.removePrefix(":devices:").substringBefore(':').replace("-", "")) }
                .map { target -> ":${module.replace('/', ':')} -> $target" }
        }

        assertWithMessage(
            "a screen module that depends on a device family it is not named for. A generic screen " +
                "reaches a family through the contributions in features/device/DeviceContributions.kt.",
        ).that(edges).isEmpty()
    }

    private fun sourcesUnder(root: String): Sequence<File> =
        File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }

    private fun hiltModulesUnder(root: String): List<String> =
        sourcesUnder(root)
            .filter { file -> file.readLines().any { it.trim() == "@Module" } }
            .map { it.relativeTo(File(root)).invariantSeparatorsPath }
            .toList()

    private companion object {
        const val AppRoot = "src/main/kotlin/tech/mmarca/openwatch"

        /** `devices/<vendor>`: the families' platform halves. Not the shared core, not a protocol module. */
        val DeviceModules: List<String> = LibraryModuleRoots.keys
            .filter { it.split('/').let { parts -> parts.size == 2 && parts[0] == "devices" && parts[1] != "core" } }
            .sorted()

        /** `feature/<name>`: the screens' modules. */
        val FeatureModules: List<String> = LibraryModuleRoots.keys.filter { it.startsWith("feature/") }.sorted()

        val FamilyBinding = Regex("""@IntoMap\s+@StringKey\("\w+"\)\s+abstract fun \w+\([^)]*\): DeviceFamily""")
        val ProjectDependency = Regex("""project\("([:\w-]+)"\)""")

        /** A dependency of the module's own code, not of its tests or fixtures. */
        val MainProjectDependency = Regex("""^\s*(?:api|implementation|compileOnly)\(project\("([:\w-]+)"\)\)""", RegexOption.MULTILINE)
    }
}
