package tech.mmarca.openwatch.architecture

import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * `runCatching` around an `await()` swallows the cancellation of the coroutine it runs
 * in; `runSuspendCatching` rethrows it. A text rule, so it sees only the plain case of an
 * `await()` on the same line, but that is the case that reached the Garmin link. A
 * teardown that must finish keeps `runCatching` with "on purpose" on the line above.
 */
class SuspendRunCatchingRatchetTest {

    @Test
    fun `no runCatching wraps an await`() {
        val offenders = roots().flatMap { root -> File(root).walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val lines = file.readLines()
                lines.indices
                    .filter { index -> SwallowingAwait.containsMatchIn(lines[index].substringBefore("//")) }
                    .filterNot { index -> index > 0 && "on purpose" in lines[index - 1] }
                    .map { index -> "${file.name}:${index + 1}" }
            }
            .toList()

        assertWithMessage("use runSuspendCatching (core/performance) around a suspend call")
            .that(offenders)
            .isEmpty()
    }

    private fun roots() = listOf(
        "src/main/kotlin/tech/mmarca/openwatch",
        "../wear/src/main/kotlin/tech/mmarca/openwatch",
        "../wear/recording-core/src/main/kotlin/tech/mmarca/openwatch",
    )

    private companion object {
        val SwallowingAwait = Regex("""\brunCatching\s*\{[^}]*\.await\(""")
    }
}
