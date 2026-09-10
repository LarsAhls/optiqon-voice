package se.optiqon.voice.domain.access

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * [runCatching] for suspending work, minus the one throwable that is not a failure.
 *
 * `runCatching` catches `Throwable`, so a coroutine that is cancelled while waiting — the user
 * left the screen, the identity scope was torn down — comes back as `Result.failure`. The caller
 * then carries on in a coroutine that is already dead and records a failure that never happened.
 * In this app that failure feeds `AccessRefresher`'s backoff, which ends at `grace_expired` and
 * stops dictation, so navigating away a few times was enough to make the app block itself.
 *
 * The check is `ensureActive`, not `catch (CancellationException)`, because the two cancellations
 * are not the same thing. If *this* coroutine is cancelled there is nobody to hand a result to
 * and the cancellation belongs upwards. If the work we called was cancelled while we are still
 * alive — a Firebase task that cancels itself, a scope that took its children down — then we
 * have a genuine absence of an answer, and swallowing it would leave the caller to complete
 * silently having reported nothing.
 */
internal suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (throwable: Throwable) {
        coroutineContext.ensureActive()
        Result.failure(throwable)
    }
