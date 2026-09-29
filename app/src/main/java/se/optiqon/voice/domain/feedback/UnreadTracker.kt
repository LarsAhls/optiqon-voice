package se.optiqon.voice.domain.feedback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import se.optiqon.voice.domain.access.AuthGateway

/**
 * The unread set the Feedback list shows, kept for one account at a time (FS-S468).
 *
 * Firestore is the authority: [refresh] asks [CaseUnread] for the markers every time the list is
 * loaded, and [opened] moves the marker there before the case stops showing as unread. Kept here
 * is only what this screen itself wrote, so that a list read which crossed a marker write in
 * flight cannot bring a case back as unread that the owner has just opened. A newer public reply
 * still does: a case is hidden only up to the revision that was actually recorded.
 *
 * All of it is forgotten when the signed-in account changes, and an account that is not
 * approved gets an empty set and no writes ([CaseUnread] decides that).
 */
class UnreadTracker(
    private val unread: CaseUnread,
    private val auth: AuthGateway
) {
    private val lock = Mutex()
    private var owner: String? = null
    private var current: Set<String> = emptySet()
    private val recorded = mutableMapOf<String, Long>()

    /** The ids to show as unread among [cases]; empty whenever the answer is not known. */
    suspend fun refresh(cases: List<FeedbackCase>): Set<String> {
        val uid = auth.currentUid ?: return lock.withLock { forget(null); emptySet() }
        val fresh = unread.unread(cases)
        return lock.withLock {
            if (auth.currentUid != uid) return@withLock emptySet()
            if (owner != uid) forget(uid)
            val revs = cases.associate { it.id to it.publicRev }
            current = fresh.filterTo(mutableSetOf()) { id -> (revs[id] ?: 0L) > (recorded[id] ?: 0L) }
            current
        }
    }

    /**
     * The owner opened [case] as the list showed it. Records it as seen in Firestore when it was
     * unread, and returns the unread set afterwards. A case not shown as unread costs no write;
     * a failed write leaves it unread, to be tried again on the next open.
     */
    suspend fun opened(case: FeedbackCase): Set<String> {
        val uid = auth.currentUid ?: return emptySet()
        val (known, before) = lock.withLock {
            if (owner != uid) forget(uid)
            (case.id in current) to recorded[case.id]
        }
        if (!known || (before != null && before >= case.publicRev)) return lock.withLock { current }
        val ok = unread.markSeen(case, seenBefore = null)
        return lock.withLock {
            if (owner != uid || auth.currentUid != uid) return@withLock emptySet()
            if (ok) {
                recorded[case.id] = maxOf(recorded[case.id] ?: 0L, case.publicRev)
                current = current - case.id
            }
            current
        }
    }

    private fun forget(uid: String?) {
        owner = uid
        current = emptySet()
        recorded.clear()
    }
}
