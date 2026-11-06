package tech.mmarca.openwatch.architecture

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * A ratchet on file length: no new file passes [MaxLines], and a listed file may shrink, never grow.
 *
 * The largest files are also the ones changed most often, so each change there costs more to review.
 * When this fails on a new file, split the file. When it fails on a listed file, move the new code
 * out. When you shrink a listed file, lower its ceiling or remove it in the same commit.
 */
class FileSizeRatchetTest {

    private val lineCounts: Map<String, Int> by lazy {
        (File(SourceRoot).walkTopDown() + File(SharedRoot).walkTopDown() + File(DomainRoot).walkTopDown())
            .filter { it.isFile && it.extension == "kt" }
            .associate { it.rootRelativePath() to it.readLines().size }
    }

    @Test
    fun `no new file passes the limit`() {
        val unlisted = lineCounts.filter { (path, lines) -> lines > MaxLines && path !in Ceilings }

        assertWithMessage("files over $MaxLines lines that are not listed. Split them: $unlisted")
            .that(unlisted)
            .isEmpty()
    }

    @Test
    fun `a listed file does not grow past its ceiling`() {
        val grown = Ceilings.filter { (path, ceiling) -> (lineCounts[path] ?: 0) > ceiling }
            .mapValues { (path, ceiling) -> "${lineCounts[path]} lines, ceiling $ceiling" }

        assertWithMessage("listed files grew past their ceiling. Move the new code out: $grown")
            .that(grown)
            .isEmpty()
    }

    @Test
    fun `the list holds only files that still need it`() {
        // A stale entry would let a file grow back unseen.
        val stale = Ceilings.keys.filter { path -> (lineCounts[path] ?: 0) <= MaxLines }

        assertWithMessage("listed files are gone or under $MaxLines lines. Remove them from the list: $stale")
            .that(stale)
            .isEmpty()
    }

    private companion object {
        const val SourceRoot = "src/main/kotlin/tech/mmarca/openwatch"

        /** The shared link module is held to the same limit; its paths are reported with a `wearlink/` prefix. */
        const val SharedRoot = "../wearlink/src/main/kotlin/tech/mmarca/openwatch"

        /** The domain module keeps the app's package names, so its paths are reported as the app's. */
        const val DomainRoot = "../domain/src/main/kotlin/tech/mmarca/openwatch"

        fun File.rootRelativePath(): String = when {
            invariantSeparatorsPath.contains("/wearlink/") -> "wearlink/" + relativeTo(File(SharedRoot)).invariantSeparatorsPath
            invariantSeparatorsPath.contains("/domain/src/") -> relativeTo(File(DomainRoot)).invariantSeparatorsPath
            else -> relativeTo(File(SourceRoot)).invariantSeparatorsPath
        }
        const val MaxLines = 800

        /** The files over the limit when the device code moved here (2026-11-06). Each ceiling leaves room for small fixes. */
        val Ceilings: Map<String, Int> = mapOf(
            "devices/garmin/wellness/GarminFitWellness.kt" to 1250,
            "features/watches/WatchDeviceScreen.kt" to 1100,
            "devices/garmin/GarminMessages.kt" to 1100,
            "devices/garmin/GarminSession.kt" to 950,
        )
    }
}
