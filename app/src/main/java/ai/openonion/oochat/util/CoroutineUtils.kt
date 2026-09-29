package ai.openonion.oochat.util

import kotlinx.coroutines.CancellationException

/**
 * Like the stdlib `runCatching`, but rethrows [CancellationException] instead
 * of wrapping it in the returned [Result].
 *
 * The stdlib `runCatching`'s blanket `catch (e: Throwable)` breaks structured
 * concurrency: when a coroutine's scope is cancelled (e.g. a ViewModel is
 * cleared while a suspend call is in flight), the resulting
 * `CancellationException` should propagate so the coroutine machinery knows
 * to stop — not be caught, logged, and turned into a `Result.failure` that
 * looks like an ordinary error. Concretely, this was firing bogus
 * "Connection lost"/"failed" UI states during screen teardown, right as the
 * ViewModel that owned that state was being destroyed.
 */
suspend inline fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
