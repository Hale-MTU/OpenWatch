package tech.mmarca.openwatch.architecture

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * The direction the packages of `:app` depend in, until Gradle modules hold it.
 *
 * Screens (`features`, `ui`, `navigation`) sit on top and nothing imports them. One device
 * integration never imports another, and the device core and the platform sources
 * (notifications, media, weather) import none. The layers below the device integrations
 * (`data`, `healthconnect`, `core`, `comaps`, `activityimport`, `sensors`) import no
 * integration. OpenLink still names the integrations; the allow-list holds those files and
 * only shrinks.
 */
class PackageDirectionTest {

    @Test
    fun `imports follow the package direction`() {
        val offenders = File(Root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val from = packageOf(file)
                ImportLine.findAll(file.readText()).map { it.groupValues[1] }
                    .map { target -> target.split('.') }
                    .filter { target -> forbidden(from, target) }
                    .map { target -> "${file.relativeTo(File(Root))} -> ${target.take(2).joinToString(".")}" }
            }
            .filterNot { line -> Allowed.any { line.startsWith(it) } }
            .distinct()
            .toList()

        assertWithMessage("an import against the package direction (see this test's KDoc)").that(offenders).isEmpty()
    }

    @Test
    fun `the allow-list holds no stale entries`() {
        val stale = Allowed.filterNot { entry ->
            val file = File(Root, entry.substringBefore(" ->"))
            file.isFile && ImportLine.findAll(file.readText()).any { it.groupValues[1].startsWith(entry.substringAfter("-> ")) }
        }
        assertWithMessage("remove these from the allow-list").that(stale).isEmpty()
    }

    /** `features`, or `devices/garmin` for an integration, or `root` for a file at the top. */
    private fun packageOf(file: File): List<String> =
        file.relativeTo(File(Root)).invariantSeparatorsPath.split('/').dropLast(1).let { dirs ->
            if (dirs.firstOrNull() == "devices") dirs.take(2) else dirs.take(1)
        }

    private fun forbidden(from: List<String>, target: List<String>): Boolean {
        val top = target.first()
        val fromTop = from.firstOrNull() ?: return false
        if (fromTop in Screens) return false
        if (top in Screens) return true
        val targetIntegration = target.getOrNull(1)?.takeIf { top == "devices" && it in Integrations }
        if (fromTop == "devices") {
            val fromSub = from.getOrNull(1)
            if (top in setOf("link", "di")) return true
            if (targetIntegration != null && targetIntegration != fromSub) return true
        }
        if (fromTop in BelowIntegrations && targetIntegration != null) return true
        if (fromTop in setOf("data", "healthconnect") && top == "devices") return true
        return false
    }

    private companion object {
        const val Root = "src/main/kotlin/tech/mmarca/openwatch"
        val ImportLine = Regex("""^import tech\.mmarca\.openwatch\.([\w.]+)""", RegexOption.MULTILINE)
        val Screens = setOf("features", "ui", "navigation")
        val Integrations = setOf("garmin", "wearos", "xiaomi")
        val BelowIntegrations = setOf("data", "healthconnect", "core", "comaps", "activityimport", "sensors")

        /** Imports against the direction, each with its reason. None left: a new one needs one. */
        val Allowed = emptySet<String>()
    }
}
