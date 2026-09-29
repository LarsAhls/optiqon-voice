package se.optiqon.voice.domain.feedback

import se.optiqon.voice.domain.access.AuthGateway

/**
 * Where the account's read markers live: `users/{uid}/caseReads/{caseId}` { seenPublicRev,
 * readAt } (FS-S468). The rules let only an approved owner read or move them, only forward, and
 * never past the case's `publicRev`.
 */
interface CaseReads {
    /** The account's markers by case id; null when they could not be read. */
    suspend fun seen(uid: String): Map<String, Long>?

    suspend fun markSeen(uid: String, caseId: String, publicRev: Long): RemoteResult
}

/**
 * Unread state of the signed-in account's cases.
 *
 * A case is unread while its `publicRev` — moved once per public support reply or status change,
 * never by an internal note, a read or a system event — is ahead of the account's marker. The
 * account's own messages never make its own case unread.
 *
 * Nothing is told to an account that is not approved: a revoked or pending account gets no
 * answer at all, so it has no side channel into whether support replied. Markers belong to one
 * uid; after an account switch the other account's markers are read, never these.
 */
class CaseUnread(
    private val reads: CaseReads,
    private val auth: AuthGateway,
    private val approval: ApprovalCheck
) {

    /** Ids of the cases with public activity the account has not seen. Empty when unknown. */
    suspend fun unread(cases: List<FeedbackCase>): Set<String> {
        val uid = auth.currentUid ?: return emptySet()
        if (!approval.isApproved()) return emptySet()
        val seen = reads.seen(uid) ?: return emptySet()
        if (auth.currentUid != uid) return emptySet()
        return cases.filter { isUnread(it.publicRev, seen[it.id]) }.map { it.id }.toSet()
    }

    /**
     * Records that the owner has seen [case] as it stands. A marker only moves forward, so an
     * older view opened late never makes a newer reply unread again, and a case already seen
     * costs no write.
     */
    suspend fun markSeen(case: FeedbackCase, seenBefore: Long?): Boolean {
        val uid = auth.currentUid ?: return false
        if (!approval.isApproved()) return false
        if (!isUnread(case.publicRev, seenBefore)) return true
        return reads.markSeen(uid, case.id, case.publicRev) == RemoteResult.Ok
    }

    companion object {
        fun isUnread(publicRev: Long, seen: Long?): Boolean = publicRev > (seen ?: 0L)
    }
}
