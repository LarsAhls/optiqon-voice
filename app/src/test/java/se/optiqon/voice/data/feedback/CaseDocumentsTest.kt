package se.optiqon.voice.data.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.data.feedback.CaseDocuments.Quota
import se.optiqon.voice.data.feedback.CaseDocuments.QuotaWrite
import se.optiqon.voice.domain.feedback.RemoteResult
import se.optiqon.voice.domain.sync.SendFailure

/**
 * The documents the app writes, against the key lists `firestore.rules` accepts. A key the
 * rules do not list is a PERMISSION_DENIED in production, and the outbox would park the row.
 * The lists below are copied from the rules' `hasOnly` clauses; the rules suite proves the
 * other side.
 */
class CaseDocumentsTest {

    @Test
    fun `a new case carries exactly the keys the rules allow`() {
        val doc = CaseDocuments.newCase("u", "t", "b")
        assertEquals(
            setOf(
                "ownerUid", "title", "body", "statusCache", "lastStatusEventId",
                "attachmentCount", "activeAttachmentCount", "createdAt", "updatedAt", "lastActivityAt"
            ),
            doc.keys
        )
        assertEquals("Mottaget", doc["statusCache"])
        assertEquals(null, doc["lastStatusEventId"])
    }

    @Test
    fun `a message is a public message by its author`() {
        val doc = CaseDocuments.newMessage("u", "b")
        assertEquals(setOf("type", "visibility", "actorUid", "body", "attachmentCount", "createdAt"), doc.keys)
        assertEquals("message", doc["type"])
        assertEquals("public", doc["visibility"])
        assertEquals("u", doc["actorUid"])
    }

    @Test
    fun `an attachment always names its message, even when there is none`() {
        val doc = CaseDocuments.attachment("u", "c", null, 10)
        assertEquals(setOf("ownerUid", "caseId", "messageId", "maxBytes", "createdAt"), doc.keys)
        assertTrue(doc.containsKey("messageId"))
        assertEquals(10L, doc["maxBytes"])
    }

    @Test
    fun `the reservation matches the attachment's ceiling`() {
        val doc = CaseDocuments.reservation("c", 10)
        assertEquals(setOf("caseId", "maxBytes", "createdAt"), doc.keys)
        assertEquals(CaseDocuments.attachment("u", "c", null, 10)["maxBytes"], doc["maxBytes"])
    }

    @Test
    fun `case and message counters touch only what the rules let them`() {
        val caseKeys = setOf("activeAttachmentCount", "attachmentCount", "attachmentFor", "lastActivityAt")
        val onOpening = CaseDocuments.caseSlotTaken("a", active = 2, opening = 1, onOpening = true)
        assertTrue(caseKeys.containsAll(onOpening.keys))
        assertEquals(3L, onOpening["activeAttachmentCount"])
        assertEquals(2L, onOpening["attachmentCount"])

        val onReply = CaseDocuments.caseSlotTaken("a", active = 2, opening = 1, onOpening = false)
        assertTrue("a reply's screenshot leaves the opening count alone", "attachmentCount" !in onReply)

        assertEquals(
            setOf("attachmentCount", "attachmentFor"),
            CaseDocuments.messageSlotTaken("a", 0).keys
        )
        assertEquals(
            setOf("activeAttachmentCount", "attachmentFor"),
            CaseDocuments.caseSlotReleased("a", 3).keys
        )
        assertEquals(2L, CaseDocuments.caseSlotReleased("a", 3)["activeAttachmentCount"])
        assertEquals(setOf("deleteRequestedAt"), CaseDocuments.tombstone().keys)
        assertEquals(setOf("lastActivityAt"), CaseDocuments.touch().keys)
    }

    @Test
    fun `the first screenshot creates the quota at one`() {
        val write = CaseDocuments.quotaAfter(null, "a", 100, 0) as QuotaWrite.Create
        assertEquals(setOf("count", "bytes", "windowStart", "windowCount", "lastUploadId"), write.fields.keys)
        assertEquals(1L, write.fields["count"])
        assertEquals(100L, write.fields["bytes"])
        assertEquals(1L, write.fields["windowCount"])
        assertEquals("a", write.fields["lastUploadId"])
    }

    @Test
    fun `within the window the count goes up and the window stays`() {
        val write = CaseDocuments.quotaAfter(Quota(3, 300, 0, 3), "a", 100, 1000) as QuotaWrite.Update
        assertEquals(4L, write.fields["count"])
        assertEquals(400L, write.fields["bytes"])
        assertEquals(4L, write.fields["windowCount"])
        assertTrue("windowStart" !in write.fields)
    }

    @Test
    fun `a full window opens a new one once it is old enough, and asks to wait before`() {
        val full = Quota(20, 2000, 0, CaseDocuments.WINDOW_MAX)
        val early = CaseDocuments.quotaAfter(full, "a", 100, CaseDocuments.WINDOW_MS)
        assertTrue(((early as QuotaWrite.Refused).result as RemoteResult.Failed).failure is SendFailure.Transient)

        val later = CaseDocuments.quotaAfter(full, "a", 100, CaseDocuments.WINDOW_MS + 1) as QuotaWrite.Update
        assertEquals(1L, later.fields["windowCount"])
        assertTrue("windowStart" in later.fields)
    }

    @Test
    fun `the lifetime caps are final`() {
        val count = CaseDocuments.quotaAfter(Quota(CaseDocuments.QUOTA_MAX_COUNT, 0, 0, 0), "a", 1, 0)
        assertTrue((count as QuotaWrite.Refused).result is RemoteResult.Denied)
        val bytes = CaseDocuments.quotaAfter(Quota(1, CaseDocuments.QUOTA_MAX_BYTES, 0, 0), "a", 1, 0)
        assertTrue((bytes as QuotaWrite.Refused).result is RemoteResult.Denied)
    }
}
