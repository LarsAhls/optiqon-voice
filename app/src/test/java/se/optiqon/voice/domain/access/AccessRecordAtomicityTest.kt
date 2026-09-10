package se.optiqon.voice.domain.access

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.data.access.AccessStateStore
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Two answers in flight at once, and the one that must lose.
 *
 * The rest of the suite tests ordering *sequentially*: one answer completes, then a second one
 * starts and is refused. That proves the predicate is right and proves nothing about when it is
 * evaluated. The failure this file is about needs both answers to be past their checks before
 * either has written — which is exactly what a check-then-write pair allows, and exactly what a
 * sequential test cannot produce.
 *
 * So the store's write is held open here. Each `record` is let as far as the store and parked
 * there; only when both are parked are they released, newest first. Under a check-then-write
 * implementation the older answer has already read "nothing stored yet" and overwrites the
 * revocation with an approval. Under a compare-and-set inside the write it re-reads at commit
 * and stands down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AccessRecordAtomicityTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    /**
     * A real store whose writes can be held at the door.
     *
     * Only `updateData` is gated; reads pass straight through, so the flows the repository
     * collects behave exactly as they do in the app. Each held write gets its own latch, in
     * arrival order, so a test can decide the commit order rather than hope for one.
     */
    private class GatedAccessStateStore(
        context: Context,
        scope: CoroutineScope
    ) : AccessStateStore(context) {

        private val real: DataStore<Preferences> =
            PreferenceDataStoreFactory.create(scope = scope) {
                File(context.cacheDir, "gated-access-${COUNTER.incrementAndGet()}.preferences_pb")
            }

        private val gates = mutableListOf<CompletableDeferred<Unit>>()

        /** Writes are let through untouched until a test says otherwise. */
        var holding: Boolean = false

        /** How many writes are currently parked at the door. */
        val arrived: Int get() = synchronized(gates) { gates.size }

        fun release(index: Int) {
            synchronized(gates) { gates[index] }.complete(Unit)
        }

        override val store: DataStore<Preferences> = object : DataStore<Preferences> {
            override val data: Flow<Preferences> get() = real.data

            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences
            ): Preferences {
                if (holding) {
                    val gate = CompletableDeferred<Unit>()
                    synchronized(gates) { gates += gate }
                    gate.await()
                }
                return real.updateData(transform)
            }
        }

        private companion object {
            val COUNTER = AtomicInteger()
        }
    }

    private class Harness(
        val store: GatedAccessStateStore,
        val auth: se.optiqon.voice.testing.FakeAuthGateway,
        val identity: ActiveIdentity,
        val repository: AccessRepository
    ) {
        fun signIn(uid: String) {
            auth.signIn(uid, "$uid@example.test")
            identity.update(uid)
        }

        fun signOut() {
            auth.clear()
            identity.update(null)
        }

        suspend fun stored(uid: String): AccessSnapshot? = store.snapshot(uid).first()
    }

    private fun TestScope.harness(): Harness {
        val store = GatedAccessStateStore(context, backgroundScope)
        val auth = se.optiqon.voice.testing.FakeAuthGateway()
        val identity = ActiveIdentity()
        val repository = AccessRepository(
            authGateway = auth,
            accessStateStore = store,
            activeIdentity = identity,
            clock = se.optiqon.voice.testing.MovableClock(),
            config = AccessConfig(),
            scope = backgroundScope
        )
        return Harness(store, auth, identity, repository)
    }

    /**
     * DataStore's own I/O is not on the test dispatcher, so virtual time alone never lets a
     * write finish; real time has to be yielded as well.
     */
    private fun TestScope.settle(rounds: Int = 30) {
        repeat(rounds) {
            runCurrent()
            @Suppress("BlockingMethodInNonBlockingContext")
            Thread.sleep(1L)
        }
        runCurrent()
    }

    @Test
    fun `an older approval that passed its check before a newer revocation still loses`() =
        runTest {
            val h = harness()
            h.signIn("uid-a")
            val epoch = h.identity.current.value!!
            val older = h.identity.nextSeq()
            val newer = h.identity.nextSeq()
            h.store.holding = true

            var approvedWon: Boolean? = null
            var revokedWon: Boolean? = null

            // The approval starts first and gets as far as the store, where it waits.
            backgroundScope.launch {
                approvedWon = h.repository.record(epoch, older, AccountStatus.APPROVED)
            }
            settle()
            assertEquals("the approval should be parked at the write", 1, h.store.arrived)

            // The revocation follows, and is parked too: both are now past every check a
            // check-then-write implementation would have made.
            backgroundScope.launch {
                revokedWon = h.repository.record(epoch, newer, AccountStatus.REVOKED)
            }
            settle()
            assertEquals("both answers should be parked", 2, h.store.arrived)

            h.store.release(1)
            settle()
            h.store.release(0)
            settle()

            assertEquals(true, revokedWon)
            assertEquals("the older answer must stand down at the commit", false, approvedWon)
            val snapshot = h.stored("uid-a")
            assertEquals(AccountStatus.REVOKED, snapshot?.status)
            assertEquals(newer, snapshot?.seq)
        }

    @Test
    fun `an answer whose account signed out between the check and the write stores nothing`() =
        runTest {
            val h = harness()
            h.signIn("uid-a")
            val epoch = h.identity.current.value!!
            val seq = h.identity.nextSeq()
            h.store.holding = true

            var won: Boolean? = null
            backgroundScope.launch {
                won = h.repository.record(epoch, seq, AccountStatus.APPROVED)
            }
            settle()
            assertEquals(1, h.store.arrived)

            // The check has been passed. The user signs out anyway.
            h.signOut()
            h.store.release(0)
            settle()

            assertEquals(false, won)
            assertNull("a signed-out account must not be left approved", h.stored("uid-a"))
        }

    @Test
    fun `an answer overtaken by an account switch stores nothing for either account`() = runTest {
        val h = harness()
        h.signIn("uid-a")
        val epoch = h.identity.current.value!!
        val seq = h.identity.nextSeq()
        h.store.holding = true

        var won: Boolean? = null
        backgroundScope.launch { won = h.repository.record(epoch, seq, AccountStatus.APPROVED) }
        settle()
        assertEquals(1, h.store.arrived)

        h.signIn("uid-b")
        h.store.release(0)
        settle()

        assertEquals(false, won)
        assertNull(h.stored("uid-a"))
        assertNull(h.stored("uid-b"))
    }

    /**
     * The control. Nothing above is worth having if it also refuses the answer that should win:
     * a genuinely newer verdict replaces a stored one, in both directions.
     */
    @Test
    fun `a newer verdict still replaces an older one, revoked or approved`() = runTest {
        val h = harness()
        h.signIn("uid-a")
        val epoch = h.identity.current.value!!

        assertTrue(h.repository.record(epoch, h.identity.nextSeq(), AccountStatus.REVOKED))
        settle()
        assertEquals(AccountStatus.REVOKED, h.stored("uid-a")?.status)

        assertTrue(h.repository.record(epoch, h.identity.nextSeq(), AccountStatus.APPROVED))
        settle()
        assertEquals(AccountStatus.APPROVED, h.stored("uid-a")?.status)

        // And an answer from further back in the read order still does not.
        assertFalse(h.repository.record(epoch, 1L, AccountStatus.REVOKED))
        settle()
        assertEquals(AccountStatus.APPROVED, h.stored("uid-a")?.status)
    }
}
