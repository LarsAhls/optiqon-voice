package se.optiqon.voice.domain.access

/**
 * Told about a server verdict for [uid] just before [AccessRepository] stores it, with the
 * verdict it is about to replace.
 *
 * Called *before* the commit on purpose. Whatever a listener does about a revocation is then
 * done by the time the revocation is on disk, so a process that dies in between leaves nothing
 * to make up later. The price is that an answer about to lose to a newer one is heard too; a
 * listener must therefore only ever be conservative.
 *
 * It can never stop a verdict from being stored: [AccessRepository] swallows its failures.
 */
fun interface ServerVerdictListener {
    suspend fun beforeRecord(uid: String, previous: AccountStatus?, status: AccountStatus)

    /**
     * As above, with the approval generations as well: [previousGeneration] is null when there
     * was no stored verdict. The default ignores them, so a listener that has no use for them
     * need not say so.
     */
    suspend fun beforeRecord(
        uid: String,
        previous: AccountStatus?,
        status: AccountStatus,
        previousGeneration: Long?,
        generation: Long
    ) = beforeRecord(uid, previous, status)

    companion object {
        val NONE = ServerVerdictListener { _, _, _ -> }
    }
}
