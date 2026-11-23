package tech.mmarca.openwatch.architecture

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * Who wires the device layer. A device family that is a Gradle module of its own
 * (`:devices:<vendor>`) binds itself in its own Hilt module, its family among the rest, so a new
 * device is a new module and a line in the app's build file. Only the app and those modules
 * declare Hilt modules: not the device code still in the app (`di/DevicesModule.kt` binds it),
 * not the shared device core, a contract module or a protocol module. A device module depends
 * on no other device module, nor on the app. The architecture document says so.
 */
class DevicesLayeringTest {

    @Test
    fun `only the app and the device modules declare Hilt modules, and not in the app's devices`() {
        val misplaced = (mapOf("app" to AppRoot) + LibraryModuleRoots)
            .flatMap { (module, root) -> hiltModulesUnder(root).map { path -> module to path } }
            .filterNot { (module, path) -> (module == "app" && !path.startsWith("devices/")) || module in DeviceModules }
            .map { (module, path) -> "$module: $path" }

        assertWithMessage(
            "Hilt modules outside the app and the device modules, or in the app's devices/. A family " +
                "binds itself in its own module; what every family shares is bound in di/DevicesModule.kt.",
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
                    target == ":app" || target.startsWith(":features") || anotherFamily
                }
                .map { target -> "$path -> $target" }
        }

        assertWithMessage("a device module that depends on another family, a feature or the app").that(edges).isEmpty()
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

        val FamilyBinding = Regex("""@IntoMap\s+@StringKey\("\w+"\)\s+abstract fun \w+\([^)]*\): DeviceFamily""")
        val ProjectDependency = Regex("""project\("([:\w-]+)"\)""")
    }
}
