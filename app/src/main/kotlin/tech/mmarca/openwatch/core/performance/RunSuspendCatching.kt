package tech.mmarca.openwatch.core.performance

import kotlinx.coroutines.CancellationException

/**
 * [runCatching] for a block that suspends: every failure becomes a [Result], except
 * cancellation, which is rethrown. `runCatching` around a suspend call swallows the
 * cancellation of the coroutine it runs in, which then goes on working until its next
 * suspension point, or forever in a loop that catches each one. A timeout from
 * `withTimeoutOrNull` is a cancellation too, and still ends in its null.
 *
 * Teardown in a `finally` that must run to its end keeps `runCatching` on purpose, marked
 * so beside it.
 */
inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
