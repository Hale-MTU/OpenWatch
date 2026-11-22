package tech.mmarca.openwatch.core.performance

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunSuspendCatchingTest {

    @Test
    fun `a failure is a result, as with runCatching`() {
        val result = runSuspendCatching { error("boom") }

        assertTrue(result.isFailure)
    }

    @Test
    fun `a cancelled coroutine stops instead of going on`() = runTest {
        val never = CompletableDeferred<Unit>()
        var after = 0
        val job = launch {
            runSuspendCatching { never.await() }
            after += 1
        }
        runCurrent()

        job.cancel()
        runCurrent()

        assertEquals(0, after)
    }

    @Test
    fun `a timeout still ends in withTimeoutOrNull's null`() = runTest {
        val never = CompletableDeferred<Unit>()

        val result = withTimeoutOrNull(1_000) { runSuspendCatching { never.await() } }

        assertNull(result)
    }

    @Test
    fun `cancellation is rethrown, not wrapped`() {
        val thrown = runCatching { runSuspendCatching { throw CancellationException("stop") } }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
    }
}
