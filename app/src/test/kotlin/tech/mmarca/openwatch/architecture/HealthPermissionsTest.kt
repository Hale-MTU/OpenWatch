package tech.mmarca.openwatch.architecture

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.Record
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import tech.mmarca.openwatch.healthconnect.HealthDataCatalog
import tech.mmarca.openwatch.healthconnect.HealthDataSection

/**
 * The health permissions the two manifests declare, against [HealthDataCatalog].
 *
 * A store reviewer reads the manifest line by line against the app, and the app shows the
 * catalog. So a permission is declared once, is in the catalog, and stands for a record
 * the code builds; and a record an integration starts to build is in the catalog before it
 * ships.
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

    @Test
    fun `the phone manifest declares what the catalog lists, and nothing else`() {
        assertWithMessage(
            "app/src/main/AndroidManifest.xml and HealthDataCatalog disagree. A new permission needs " +
                "its row in the catalog, PRIVACY.md and docs/app/permissions.md, and Play's health declaration.",
        )
            .that(declaredHealthPermissions(PhoneManifest).toSortedSet())
            .isEqualTo(HealthDataCatalog.phonePermissions)
    }

    @Test
    fun `the watch manifest declares what the catalog lists, and nothing else`() {
        assertWithMessage("wear/src/main/AndroidManifest.xml and HealthDataCatalog's watch section disagree")
            .that(declaredHealthPermissions(WatchManifest).toSortedSet())
            .isEqualTo(HealthDataCatalog.watchPermissions)
    }

    @Test
    fun `an integration builds every record its section lists`() {
        for ((section, roots) in SourcesBySection) {
            val built = recordsBuiltUnder(roots)
            val listed = HealthDataCatalog.sections.getValue(section).mapNotNull { it.record?.simpleName }

            assertWithMessage("$section lists a record no file under $roots builds: remove the row and its permission")
                .that(listed.filterNot { it in built })
                .isEmpty()
        }
    }

    @Test
    fun `a record an integration builds has its permission in the section`() {
        for ((section, roots) in SourcesBySection) {
            val granted = HealthDataCatalog.sections.getValue(section).map { it.permission }.toSet()
            val uncovered = recordsBuiltUnder(roots).filterNot { name ->
                @Suppress("UNCHECKED_CAST")
                val record = Class.forName("$RecordsPackage.$name").kotlin as kotlin.reflect.KClass<out Record>
                HealthPermission.getWritePermission(record) in granted
            }

            assertWithMessage("$section writes a record whose permission the catalog does not list for it")
                .that(uncovered)
                .isEmpty()
        }
    }

    /** The Health Connect record classes a file under [roots] imports and constructs. */
    private fun recordsBuiltUnder(roots: List<String>): Set<String> =
        roots.asSequence()
            .flatMap { File("$SourceRoot/$it").walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                RecordImport.findAll(text).map { it.groupValues[1] }.filter { Regex("""\b$it\(""").containsMatchIn(text) }
            }
            .toSet()

    private companion object {
        const val PhoneManifest = "src/main/AndroidManifest.xml"
        const val WatchManifest = "../wear/src/main/AndroidManifest.xml"
        const val SourceRoot = "src/main/kotlin/tech/mmarca/openwatch"
        const val RecordsPackage = "androidx.health.connect.client.records"
        val RecordImport = Regex("""import androidx\.health\.connect\.client\.records\.(\w+Record)\b""")

        /** Where each integration's Health Connect records are built. */
        val SourcesBySection = mapOf(
            HealthDataSection.GARMIN_WATCH to
                listOf("devices/garmin", "devices/core/sync", "activityimport", "healthconnect/ActivityRecordWriter.kt"),
            HealthDataSection.WEAROS_WATCH to listOf("devices/wearos", "devices/core/sync"),
            HealthDataSection.SCALE to listOf("devices/xiaomi"),
        )
    }
}

/** The `android.permission.health.*` names a manifest file asks for, in file order, repeats kept. */
internal fun declaredHealthPermissions(manifestPath: String): List<String> =
    UsesHealthPermission.findAll(File(manifestPath).readText()).map { it.groupValues[1] }.toList()

private val UsesHealthPermission = Regex("""<uses-permission\s+android:name="(android\.permission\.health\.[A-Z0-9_]+)"""")
