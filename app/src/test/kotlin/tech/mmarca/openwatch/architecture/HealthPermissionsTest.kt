package tech.mmarca.openwatch.architecture

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * The health permissions the two manifests declare. A store reviewer reads the
 * manifest line by line against the app, so it stays free of leftovers.
 */
class HealthPermissionsTest {

    @Test
    fun `no health permission is declared twice`() {
        for (manifest in listOf(PhoneManifest, WatchManifest)) {
            val declared = declaredHealthPermissions(manifest)
            val twice = declared.groupingBy { it }.eachCount().filterValues { it > 1 }.keys

            assertWithMessage("declared more than once in $manifest").that(twice).isEmpty()
        }
    }

    private companion object {
        const val PhoneManifest = "src/main/AndroidManifest.xml"
        const val WatchManifest = "../wear/src/main/AndroidManifest.xml"
    }
}

/** The `android.permission.health.*` names a manifest file asks for, in file order, repeats kept. */
internal fun declaredHealthPermissions(manifestPath: String): List<String> =
    UsesHealthPermission.findAll(File(manifestPath).readText()).map { it.groupValues[1] }.toList()

private val UsesHealthPermission = Regex("""<uses-permission\s+android:name="(android\.permission\.health\.[A-Z0-9_]+)"""")
