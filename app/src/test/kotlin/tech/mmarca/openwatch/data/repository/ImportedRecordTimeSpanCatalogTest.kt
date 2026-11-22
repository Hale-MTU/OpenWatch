package tech.mmarca.openwatch.data.repository

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.mmarca.openwatch.healthconnect.HealthDataCatalog

/**
 * A refused batch is diagnosed by the stored records' time spans (`timeSpan()` in
 * :core:health-api's `ImportedRecordWrites.kt`), so every record type the app's catalog writes
 * needs a case there. From `ImportedRecordWritesTest`, which moved with the helpers; the
 * catalog is the app's.
 */
class ImportedRecordTimeSpanCatalogTest {

    @Test
    fun `every type the app writes has a time span, so a refusal of it can be diagnosed`() {
        val source = File("../core/health-api/src/main/kotlin/tech/mmarca/openwatch/data/repository/ImportedRecordWrites.kt").readText()
        val written = HealthDataCatalog.sections.values.flatten().mapNotNull { it.record?.simpleName }.toSet()
        val missing = written.filterNot { "is $it ->" in source }
        assertTrue("timeSpan() has no case for $missing", missing.isEmpty())
    }
}
