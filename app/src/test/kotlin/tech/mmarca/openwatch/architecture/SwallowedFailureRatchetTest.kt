package tech.mmarca.openwatch.architecture

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * A screen shows a failure as a failure. A state holder that catches an exception without a
 * name, `catch (_: Exception)`, can only be turning it into a default, and the screen then
 * shows "nothing there" for "could not read": the watch-data screen, the Health Connect setup
 * and the body profile all did. Catch it named and put `error.toScreenError()` in the state.
 * A teardown that must not fail says "on purpose" on the catch's line or the one beside it.
 */
class SwallowedFailureRatchetTest {

    @Test
    fun `no ViewModel swallows an exception into a default`() {
        val offenders = File(Root).walkTopDown()
            .filter { it.isFile && it.name.endsWith("ViewModel.kt") }
            .flatMap { file ->
                val lines = file.readLines()
                lines.indices
                    .filter { index -> Unnamed.containsMatchIn(lines[index]) }
                    .filterNot { index ->
                        (index - 1..index + 1).any { lines.getOrNull(it)?.contains("on purpose", ignoreCase = true) == true }
                    }
                    .map { index -> "${file.relativeTo(File(Root))}:${index + 1}" }
            }
            .toList()

        assertWithMessage("catch it named and show it: error = error.toScreenError()")
            .that(offenders)
            .isEmpty()
    }

    private companion object {
        const val Root = "src/main/kotlin/tech/mmarca/openwatch/features"
        val Unnamed = Regex("""catch\s*\(\s*_\s*:\s*(Exception|Throwable)\s*\)""")
    }
}
