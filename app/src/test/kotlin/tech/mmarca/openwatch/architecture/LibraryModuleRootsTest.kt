package tech.mmarca.openwatch.architecture

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * The ratchets see a library module only through [LibraryModuleRoots]. A module missing there,
 * or a root that moved, would leave its files unchecked without failing anything, so this
 * holds the list to the modules on disk under `core/` and `devices/`.
 */
class LibraryModuleRootsTest {

    @Test
    fun `every library module under core and devices is listed, and every listed root exists`() {
        val onDisk = listOf("../core", "../devices").asSequence()
            .flatMap { File(it).walkTopDown().onEnter { dir -> dir.name != "build" && dir.name != "src" } }
            .filter { it.isDirectory && File(it, "build.gradle.kts").isFile && File(it, "src/main").isDirectory }
            .map { it.relativeTo(File("..")).invariantSeparatorsPath }
            .toSet()

        assertWithMessage("modules under core/ and devices/ missing from LibraryModuleRoots")
            .that(onDisk - LibraryModuleRoots.keys)
            .isEmpty()
        assertWithMessage("LibraryModuleRoots entries with no source root on disk")
            .that(LibraryModuleRoots.filterValues { !File(it).isDirectory }.keys)
            .isEmpty()
    }
}
