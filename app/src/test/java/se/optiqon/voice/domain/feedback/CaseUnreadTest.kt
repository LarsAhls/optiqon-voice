package se.optiqon.voice.domain.feedback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.testing.SwitchableAuth

/** FS-S468 unread state: public activity only, forward only, per account, nothing for the unapproved. */
class CaseUnreadTest {

    private class FakeReads : CaseReads {
        val markers = mutableMapOf<String, MutableMap<String, Long>>()
        val writes = mutableListOf<Triple<String, String, Long>>()
        var failRead = false
        var onRead: () -> Unit = {}

        override suspend fun seen(uid: String): Map<String, Long>? {
            onRead()
            return if (failRead) null else markers[uid].orEmpty().toMap()
        }

        override suspend fun markSeen(uid: String, caseId: String, publicRev: Long): RemoteResult {
            writes += Triple(uid, caseId, publicRev)
            val mine = markers.getOrPut(uid) { mutableMapOf() }
            mine[caseId] = maxOf(mine[caseId] ?: 0L, publicRev)
            return RemoteResult.Ok
        }
    }

    private val reads = FakeReads()
    private val auth = SwitchableAuth("uid-a")
    private var approved = true
    private val unread = CaseUnread(reads, auth) { approved }

    private fun case(id: String, publicRev: Long) =
        FeedbackCase(id, "t", "b", "Mottaget", 1L, 1L, 0, false, publicRev)

    @Test
    fun `a case is unread only while its public revision is ahead of the marker`() {
        assertFalse(CaseUnread.isUnread(0L, null))
        assertTrue(CaseUnread.isUnread(1L, null))
        assertFalse(CaseUnread.isUnread(2L, 2L))
        assertTrue(CaseUnread.isUnread(3L, 2L))
        assertFalse("a marker ahead never makes it unread", CaseUnread.isUnread(2L, 3L))
    }

    @Test
    fun `only cases with unseen public activity are unread`() = runTest {
        reads.markers["uid-a"] = mutableMapOf("c1" to 2L)
        val result = unread.unread(listOf(case("c1", 2L), case("c2", 1L), case("c3", 0L)))
        assertEquals(setOf("c2"), result)
    }

    @Test
    fun `one account's markers say nothing about another's`() = runTest {
        reads.markers["uid-b"] = mutableMapOf("c1" to 5L)
        assertEquals(setOf("c1"), unread.unread(listOf(case("c1", 5L))))

        auth.currentUid = "uid-b"
        assertEquals(emptySet<String>(), unread.unread(listOf(case("c1", 5L))))
    }

    @Test
    fun `a revoked or pending account is told nothing`() = runTest {
        approved = false
        assertEquals(emptySet<String>(), unread.unread(listOf(case("c1", 3L))))
        assertFalse(unread.markSeen(case("c1", 3L), null))
        assertTrue(reads.writes.isEmpty())
    }

    @Test
    fun `signed out or unreadable markers give no answer rather than a wrong one`() = runTest {
        reads.failRead = true
        assertEquals(emptySet<String>(), unread.unread(listOf(case("c1", 3L))))
        auth.currentUid = null
        reads.failRead = false
        assertEquals(emptySet<String>(), unread.unread(listOf(case("c1", 3L))))
    }

    @Test
    fun `an account switch during the read answers nothing`() = runTest {
        reads.onRead = { auth.currentUid = "uid-b" }
        assertEquals(emptySet<String>(), unread.unread(listOf(case("c1", 3L))))
    }

    @Test
    fun `marking seen moves the marker forward to the case's revision`() = runTest {
        assertTrue(unread.markSeen(case("c1", 4L), 1L))
        assertEquals(listOf(Triple("uid-a", "c1", 4L)), reads.writes)
    }

    @Test
    fun `a case already seen costs no write, and an older view never moves the marker back`() = runTest {
        assertTrue(unread.markSeen(case("c1", 4L), 4L))
        assertTrue(unread.markSeen(case("c1", 2L), 4L))
        assertTrue(reads.writes.isEmpty())
    }
}
