package se.optiqon.voice.debug

import se.optiqon.voice.domain.access.AccessRefresher
import se.optiqon.voice.domain.access.Clock
import se.optiqon.voice.domain.access.RefreshOutcome
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-only switches for the access smoke (Gate G3 steps 6 and 9).
 *
 * Two things a smoke test cannot otherwise provoke on a real device without root:
 * a backend refresh that *fails* (as opposed to one that is merely offline), and the
 * passage of the 72 h grace period. Both are flipped over adb through
 * [AccessDebugReceiver]; both default to off, so a debug build that nobody pokes behaves
 * exactly like release.
 *
 * This class lives in the debug source set only. There is no release variant of it, and
 * nothing in `main` references it — see the release `AccessRuntimeModule`.
 */
@Singleton
class AccessDebugControls @Inject constructor() {
    /** When true every refresh reports [RefreshOutcome.Failed] without contacting Firestore. */
    @Volatile var failRefresh: Boolean = false

    /** Added to both wall and elapsed time, so grace expiry can be reached in seconds. */
    @Volatile var clockOffsetMs: Long = 0L
}

/** The debug [Clock]: the real sources plus whatever offset the controls currently hold. */
class DebugClock(
    private val controls: AccessDebugControls,
    private val wall: () -> Long,
    private val elapsed: () -> Long
) : Clock {
    override fun wallMs(): Long = wall() + controls.clockOffsetMs
    override fun elapsedMs(): Long = elapsed() + controls.clockOffsetMs
}

/** The debug reader: the real one, unless a forced failure has been requested. */
class DebugRegistrationReader(
    private val delegate: AccessRefresher.RegistrationReader,
    private val controls: AccessDebugControls
) : AccessRefresher.RegistrationReader {
    override suspend fun refresh(): RefreshOutcome =
        if (controls.failRefresh) {
            RefreshOutcome.Failed(IllegalStateException("debug: forced refresh failure"))
        } else {
            delegate.refresh()
        }
}
