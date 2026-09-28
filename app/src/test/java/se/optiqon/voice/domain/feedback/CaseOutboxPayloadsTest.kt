package se.optiqon.voice.domain.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A row that cannot be read decodes to null and is parked — it is never guessed at. */
class CaseOutboxPayloadsTest {

    private fun roundTrip(p: CasePayload) =
        assertEquals(p, CaseOutboxPayloads.decode(CaseOutboxPayloads.kindOf(p), CaseOutboxPayloads.encode(p)))

    @Test
    fun `every kind survives the queue unchanged`() {
        roundTrip(CasePayload.CreateCase("c1", "Titel", "Brödtext\nmed rader"))
        roundTrip(CasePayload.Message("c1", "m1", "svar"))
        roundTrip(CasePayload.Upload("c1", "m1", "a1", "a1.png", "image/png", 1234))
        roundTrip(CasePayload.Upload("c1", null, "a2", "a2.jpg", "image/jpeg", 99))
        roundTrip(CasePayload.Tombstone("c1", "a1"))
    }

    @Test
    fun `each kind has its own marker and none is the legacy one`() {
        assertEquals(4, CaseOutboxPayloads.KINDS.size)
        assert(FeedbackPayloads.KIND !in CaseOutboxPayloads.KINDS)
    }

    @Test
    fun `garbage and unknown kinds decode to null`() {
        assertNull(CaseOutboxPayloads.decode(CaseOutboxPayloads.KIND_CREATE, "not json"))
        assertNull(CaseOutboxPayloads.decode(CaseOutboxPayloads.KIND_CREATE, "[1,2]"))
        assertNull(CaseOutboxPayloads.decode("feedback", """{"caseId":"c"}"""))
        assertNull(CaseOutboxPayloads.decode(CaseOutboxPayloads.KIND_DELETE, """{"caseId":"c"}"""))
    }

    @Test
    fun `fields of the wrong type are refused, not coerced`() {
        assertNull(CaseOutboxPayloads.decode(CaseOutboxPayloads.KIND_CREATE, """{"caseId":1,"title":"t","body":"b"}"""))
        assertNull(
            CaseOutboxPayloads.decode(
                CaseOutboxPayloads.KIND_UPLOAD,
                """{"caseId":"c","aid":"a","file":"f","mime":"image/png","bytes":"12"}"""
            )
        )
        assertNull(
            CaseOutboxPayloads.decode(
                CaseOutboxPayloads.KIND_UPLOAD,
                """{"caseId":"c","aid":"a","file":{},"mime":"image/png","bytes":12}"""
            )
        )
    }
}
