package se.optiqon.voice.data.access

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import se.optiqon.voice.domain.access.AccessSnapshot
import se.optiqon.voice.domain.access.AccountStatus
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The cache half of §3.18: signing in as somebody else must not surface the previous account's
 * verdict. Signing out, on the other hand, deletes nothing — the record simply stops being
 * consulted, which is what lets its owner come back to unfinished work.
 */
@RunWith(RobolectricTestRunner::class)
class AccessStateStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val counter = AtomicInteger()

    /** A store on a file of its own, so running order cannot decide an outcome. */
    private fun store(): TestAccessStateStore = TestAccessStateStore(
        context,
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + SupervisorJob())) {
            File(context.cacheDir, "access-${counter.incrementAndGet()}.preferences_pb")
        }
    )

    private fun approval(uid: String) = AccessSnapshot(
        uid = uid,
        status = AccountStatus.APPROVED,
        verifiedAtWallMs = 1_800_000_000_000L,
        verifiedAtElapsedMs = 5_000_000L
    )

    @Test
    fun `a verdict is read back for the account it belongs to`() = runTest {
        val store = store()
        store.record(approval("uid-a"))

        assertEquals(approval("uid-a"), store.snapshot("uid-a").first())
    }

    @Test
    fun `another account sees no verdict at all`() = runTest {
        val store = store()
        store.record(approval("uid-a"))

        assertNull(store.snapshot("uid-b").first())
    }

    @Test
    fun `two accounts on one device keep separate verdicts`() = runTest {
        val store = store()
        store.record(approval("uid-a"))
        store.record(
            AccessSnapshot("uid-b", AccountStatus.PENDING, 1_800_000_000_000L, 5_000_000L)
        )

        assertEquals(AccountStatus.APPROVED, store.snapshot("uid-a").first()?.status)
        assertEquals(AccountStatus.PENDING, store.snapshot("uid-b").first()?.status)
    }

    @Test
    fun `a value we cannot parse is treated as no verdict, never as approval`() = runTest {
        val store = store()
        store.raw.edit { it[stringPreferencesKey("status_uid-a")] = "APPROVED_MAYBE" }

        assertNull(store.snapshot("uid-a").first())
    }

    @Test
    fun `a revocation replaces the approval it supersedes`() = runTest {
        val store = store()
        store.record(approval("uid-a"))
        store.record(AccessSnapshot("uid-a", AccountStatus.REVOKED, 1_800_000_100_000L, 5_100_000L))

        assertEquals(AccountStatus.REVOKED, store.snapshot("uid-a").first()?.status)
    }
}

private class TestAccessStateStore(
    context: Context,
    val raw: DataStore<Preferences>
) : AccessStateStore(context) {
    override val store: DataStore<Preferences> get() = raw
}
