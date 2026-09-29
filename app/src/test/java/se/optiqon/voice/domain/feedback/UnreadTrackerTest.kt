package se.optiqon.voice.domain.feedback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.domain.sync.SendFailure
import se.optiqon.voice.testing.SwitchableAuth

/**
 * FS-S468: what the Feedback list shows as unread, and when opening a case records it. The
 * markers in [FakeReads] stand in for Firestore, which stays the authority throughout.
 */
class UnreadTrackerTest {

    /** Holds markers per uid with the rules' forward-only behaviour. */
    class FakeReads : CaseReads {
        val markers = mutableMapOf<String, MutableMap<String, Long>>()
        val writes = mutableListOf<Triple<String, String, Long>>()
        var writeResult: RemoteResult = RemoteResult.Ok
        var reads = 0
        /** A marker read that was taken before a write landed and is returned after it. */
        var staleRead: Map<String, Long>? = null

        override suspend fun seen(uid: String): Map<String, Long>? {
            reads++
            staleRead?.let { staleRead = null; return it }
            return markers[uid].orEmpty().toMap()
        }

        override suspend fun markSeen(uid: String, caseId: String, publicRev: Long): RemoteResult {
            writes += Triple(uid, caseId, publicRev)
            if (writeResult != RemoteResult.Ok) return writeResult
            val mine = markers.getOrPut(uid) { mutableMapOf() }
            mine[caseId] = maxOf(mine[caseId] ?: 0L, publicRev)
            return RemoteResult.Ok
        }
    }

    private val reads = FakeReads()
    private val auth = SwitchableAuth("uid-a")
    private var approved = true
    private val tracker = UnreadTracker(CaseUnread(reads, auth) { approved }, auth)

    private fun case(id: String, publicRev: Long) =
        FeedbackCase(id, "t", "b", "Mottaget", 1L, 1L, 0, false, publicRev)

    @Test
    fun `a newer public revision than the marker is unread`() = runTest {
        reads.markers["uid-a"] = mutableMapOf("c1" to 1L, "c2" to 3L)
        assertEquals(setOf("c1"), tracker.refresh(listOf(case("c1", 2L), case("c2", 3L), case("c3", 0L))))
    }

    @Test
    fun `opening an unread case records the revision it showed and clears it`() = runTest {
        tracker.refresh(listOf(case("c1", 2L)))

        assertEquals(emptySet<String>(), tracker.opened(case("c1", 2L)))
        assertEquals(listOf(Triple("uid-a", "c1", 2L)), reads.writes)
        assertEquals(emptySet<String>(), tracker.refresh(listOf(case("c1", 2L))))
    }

    @Test
    fun `opening the same or an older view again writes nothing`() = runTest {
        tracker.refresh(listOf(case("c1", 4L)))
        tracker.opened(case("c1", 4L))
        tracker.opened(case("c1", 4L))
        tracker.opened(case("c1", 2L))

        assertEquals(1, reads.writes.size)
        assertEquals(4L, reads.markers["uid-a"]!!["c1"])
    }

    @Test
    fun `an older view opened late never moves the marker back`() = runTest {
        reads.markers["uid-a"] = mutableMapOf("c1" to 5L)
        tracker.refresh(listOf(case("c1", 6L)))
        // The list showed revision 6, but the detail opened from a list read at revision 3.
        tracker.opened(case("c1", 3L))

        assertTrue(reads.markers["uid-a"]!!["c1"]!! >= 5L)
    }

    @Test
    fun `a later public reply makes an opened case unread again`() = runTest {
        tracker.refresh(listOf(case("c1", 2L)))
        tracker.opened(case("c1", 2L))

        assertEquals(setOf("c1"), tracker.refresh(listOf(case("c1", 3L))))
    }

    @Test
    fun `a list read that crossed the marker write does not bring the case back`() = runTest {
        tracker.refresh(listOf(case("c1", 2L)))
        reads.staleRead = emptyMap()
        tracker.opened(case("c1", 2L))

        assertEquals(emptySet<String>(), tracker.refresh(listOf(case("c1", 2L))))
    }

    @Test
    fun `duplicate refreshes agree and write nothing`() = runTest {
        val cases = listOf(case("c1", 2L), case("c2", 1L))
        val first = tracker.refresh(cases)
        repeat(3) { assertEquals(first, tracker.refresh(cases)) }
        assertTrue(reads.writes.isEmpty())
    }

    @Test
    fun `a failed write leaves the case unread for the next open`() = runTest {
        tracker.refresh(listOf(case("c1", 2L)))
        reads.writeResult = RemoteResult.Failed(SendFailure.Transient("x"))

        assertEquals(setOf("c1"), tracker.opened(case("c1", 2L)))
        reads.writeResult = RemoteResult.Ok
        assertEquals(emptySet<String>(), tracker.opened(case("c1", 2L)))
    }

    @Test
    fun `an account switch forgets everything the last account saw`() = runTest {
        tracker.refresh(listOf(case("c1", 2L)))
        tracker.opened(case("c1", 2L))

        auth.currentUid = "uid-b"
        // uid-b has no marker: the case is unread for it, whatever uid-a opened.
        assertEquals(setOf("c1"), tracker.refresh(listOf(case("c1", 2L))))
        tracker.opened(case("c1", 2L))
        assertEquals(listOf("uid-a", "uid-b"), reads.writes.map { it.first })
    }

    @Test
    fun `an open from the previous account's list writes nothing for the next`() = runTest {
        tracker.refresh(listOf(case("c1", 2L)))
        auth.currentUid = "uid-b"

        assertEquals(emptySet<String>(), tracker.opened(case("c1", 2L)))
        assertTrue(reads.writes.isEmpty())
    }

    @Test
    fun `a revoked or pending account sees no unread and writes no marker`() = runTest {
        approved = false
        assertEquals(emptySet<String>(), tracker.refresh(listOf(case("c1", 2L))))
        assertEquals(emptySet<String>(), tracker.opened(case("c1", 2L)))
        assertEquals(0, reads.reads)
        assertTrue(reads.writes.isEmpty())
    }

    @Test
    fun `signed out shows nothing`() = runTest {
        auth.currentUid = null
        assertEquals(emptySet<String>(), tracker.refresh(listOf(case("c1", 2L))))
        assertEquals(emptySet<String>(), tracker.opened(case("c1", 2L)))
        assertTrue(reads.writes.isEmpty())
    }
}
