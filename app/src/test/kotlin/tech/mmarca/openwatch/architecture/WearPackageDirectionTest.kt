package tech.mmarca.openwatch.architecture

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * The direction the watch app's packages depend in (`wear/src/main`), as [PackageDirectionTest]
 * holds the phone app's.
 *
 * The screens (`features`, `ui`) sit on top: the activity and the navigation host
 * ([EntryPoints]) import them, and nothing below them does. The Samsung client (`samsung`) is
 * reached through the source interfaces [SamsungSources] builds, so no recorder names it. The
 * watch's pure core, `:wear:recording-core`, imports nothing of the app: it does not depend on
 * `:wear`, so the compiler holds that. The allow-list holds the imports against the direction
 * there were when this test was written (2026-11-22), and only shrinks.
 */
class WearPackageDirectionTest {

    @Test
    fun `imports follow the watch app's package direction`() {
        val offenders = File(Root).walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val path = file.relativeTo(File(Root)).invariantSeparatorsPath
                ImportLine.findAll(file.readText()).map { it.groupValues[1].substringBefore('.') }
                    .filter { target -> forbidden(path, target) }
                    .map { target -> "$path -> $target" }
            }
            .filterNot { it in Allowed }
            .distinct()
            .toList()

        assertWithMessage("an import against the watch app's package direction (see this test's KDoc)")
            .that(offenders)
            .isEmpty()
    }

    @Test
    fun `the allow-list holds no stale entries`() {
        val stale = Allowed.filterNot { entry ->
            val file = File(Root, entry.substringBefore(" ->"))
            val target = entry.substringAfter("-> ")
            file.isFile && ImportLine.findAll(file.readText()).any { it.groupValues[1].substringBefore('.') == target }
        }
        assertWithMessage("remove these from the allow-list").that(stale).isEmpty()
    }

    /** [path] is the file's path under [Root]; [target] the first segment the import names under it. */
    private fun forbidden(path: String, target: String): Boolean {
        val from = path.substringBefore('/', missingDelimiterValue = "")
        if (target == from) return false
        if (target in Screens) return from !in Screens && path !in EntryPoints
        if (target == "samsung") return path != SamsungSources
        return false
    }

    private companion object {
        const val Root = "../wear/src/main/kotlin/tech/mmarca/openwatch/wear"
        val ImportLine = Regex("""^import tech\.mmarca\.openwatch\.wear\.([\w.]+)""", RegexOption.MULTILINE)
        val Screens = setOf("features", "ui")

        /** The activity and the navigation host, which open the screens. */
        val EntryPoints = setOf("MainActivity.kt", "OpenWatchWearApp.kt")

        /** The object graph, the one place the Samsung sources are built. */
        const val SamsungSources = "WearGraph.kt"

        /** Imports against the direction, each with its reason. */
        val Allowed = setOf(
            // The dashboard's tile model sits in the root package and carries the theme's colours.
            "MetricTile.kt -> ui",
            // The debug report's device line says whether the Samsung platform is there, and its version.
            "SensorReport.kt -> samsung",
            // The status screen asks the same, to offer the Samsung sources only where they are.
            "features/status/StatusPort.kt -> samsung",
        )
    }
}
