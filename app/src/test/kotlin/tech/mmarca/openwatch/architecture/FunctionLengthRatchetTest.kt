package tech.mmarca.openwatch.architecture

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * A ratchet on function length, as OpenVitals': the count of functions over [MaxLines] may
 * fall, never rise, and none passes [GiantLines] unless it is listed, and then it may not grow.
 * It reads the same sources as [FileSizeRatchetTest]: the phone app and its library modules,
 * the link, the watch app and its pure core.
 *
 * A function ends at the next line indented no deeper than its `fun`. That holds for this code
 * base's formatting, and it counts expression bodies too. When this fails, split the function.
 * When you shorten one, lower the number in the same commit.
 */
class FunctionLengthRatchetTest {

    private data class LongFunction(val file: String, val name: String, val lines: Int)

    private val functions: List<LongFunction> by lazy {
        Roots.flatMap { (prefix, root) ->
            File(root).walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file -> functionsIn(file, prefix + file.relativeTo(File(root)).invariantSeparatorsPath) }
                .toList()
        }
    }

    private fun functionsIn(file: File, path: String): List<LongFunction> {
        val lines = file.readLines()
        return lines.mapIndexedNotNull { start, line ->
            val match = Declaration.find(line) ?: return@mapIndexedNotNull null
            val indent = match.groupValues[1].length
            var end = lines.lastIndex
            for (index in start + 1..lines.lastIndex) {
                val candidate = lines[index]
                if (candidate.isBlank()) continue
                val trimmed = candidate.trimStart()
                // A line starting with ")" closes a wrapped parameter list, not the function.
                if (candidate.length - trimmed.length <= indent && !trimmed.startsWith(")")) {
                    end = if (trimmed == "}") index else index - 1
                    break
                }
            }
            LongFunction(path, match.groupValues[2], end - start + 1)
        }
    }

    @Test
    fun `functions over the limit do not increase`() {
        val long = functions.filter { it.lines > MaxLines }

        assertWithMessage("functions over $MaxLines lines went UP. Split the new one: ${long.sortedByDescending { it.lines }.take(10)}")
            .that(long.size)
            .isAtMost(MaxLongFunctions)
    }

    @Test
    fun `only the listed giants pass the second limit, and they do not grow`() {
        val giants = functions.filter { it.lines > GiantLines }
        val grown = giants.filter { giant -> giant.lines > (GiantCeilings["${giant.file}#${giant.name}"] ?: 0) }

        assertWithMessage("functions over $GiantLines lines that are new, or grew past their ceiling: $grown")
            .that(grown)
            .isEmpty()
    }

    private companion object {
        /**
         * Each source root with the prefix its paths are reported under. The library modules
         * ([LibraryModuleRoots]) keep the app's package names, so their paths are reported as the app's.
         */
        val Roots = listOf(
            "" to "src/main/kotlin/tech/mmarca/openwatch",
            "wearlink/" to "../wearlink/src/main/kotlin/tech/mmarca/openwatch",
            "wear/" to "../wear/src/main/kotlin/tech/mmarca/openwatch",
            "wear/recording-core/" to "../wear/recording-core/src/main/kotlin/tech/mmarca/openwatch",
        ) + LibraryModuleRoots.values.map { root -> "" to root }

        const val MaxLines = 150
        const val GiantLines = 450

        /**
         * The count on 2026-11-22, all three in the Garmin stack: the wellness dispatch (270
         * lines), the session's dispatch (201) and the radio link's pull (161). The watch app,
         * its core, the link and the library modules have none.
         */
        const val MaxLongFunctions = 3

        /** None passes the second limit; a new giant is split before it lands. */
        val GiantCeilings = emptyMap<String, Int>()

        val Declaration = Regex(
            """^(\s*)(?:(?:private|internal|public|protected|override|suspend|inline|operator|infix|tailrec|open|abstract|final)\s+)*""" +
                """fun\s+(?:<[^>]+>\s*)?(?:[\w.<>?, ]+\.)?(`[^`]+`|\w+)\s*\(""",
        )
    }
}
