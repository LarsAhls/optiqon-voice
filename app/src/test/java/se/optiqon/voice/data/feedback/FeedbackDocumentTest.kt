package se.optiqon.voice.data.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import se.optiqon.voice.data.db.entity.OutboxEntry
import se.optiqon.voice.data.db.entity.OutboxState
import se.optiqon.voice.domain.feedback.FeedbackPayload
import se.optiqon.voice.domain.feedback.FeedbackPayloads

/**
 * What actually leaves the device, if this channel is ever switched on.
 *
 * The outbox row and the document are not the same thing, and the difference is where a leak
 * would hide. The row carries `ownerUid` because ownership is how the queue decides what may
 * move; the document carries `uid` because the rules need it to check the writer against the
 * signed-in account. Everything else is what the person typed.
 */
class FeedbackDocumentTest {

    private val payload = FeedbackPayload(
        message = "the bubble vanishes",
        contact = null,
        appVersion = "1.4.2",
        androidSdk = 33,
        deviceModel = "OnePlus IN2023",
        createdAtMs = 1_757_000_000_000
    )

    private fun entryOf(kind: String = FeedbackPayloads.KIND) = OutboxEntry(
        id = "row-1",
        ownerUid = "uid-abc",
        kind = kind,
        payload = FeedbackPayloads.encode(payload),
        createdAtMs = 1_757_000_000_000,
        state = OutboxState.PENDING
    )

    @Test
    fun `the document names its writer and carries the message, and nothing more`() {
        val doc = FeedbackDocument.of(entryOf())!!

        assertEquals(
            setOf("uid", "message", "contact", "appVersion", "androidSdk", "deviceModel", "createdAtMs"),
            doc.keys
        )
        assertEquals("uid-abc", doc["uid"])
        assertEquals("the bubble vanishes", doc["message"])
        assertNull(doc["contact"])
    }

    @Test
    fun `a row of some other kind is not a feedback document`() {
        assertNull(FeedbackDocument.of(entryOf(kind = "attachment_upload")))
    }

    @Test
    fun `a row whose payload is not feedback json is refused, not half-sent`() {
        assertNull(FeedbackDocument.of(entryOf().copy(payload = "not json at all")))
    }

    @Test
    fun `the collection is its own, so no existing rule can be widened by accident`() {
        assertEquals("feedback", FeedbackDocument.COLLECTION)
        assertTrue(FeedbackDocument.COLLECTION !in setOf("users", "registrations", "access"))
    }
}
