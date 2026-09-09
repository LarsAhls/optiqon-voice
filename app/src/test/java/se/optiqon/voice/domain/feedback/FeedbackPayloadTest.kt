package se.optiqon.voice.domain.feedback

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a feedback message is allowed to contain, decided once and here.
 *
 * The risk this pins is not a crash: it is a message that quietly carries more than the
 * person meant to send. A dictation is the most private thing this app holds, so the payload
 * is a closed set of fields — the words typed into the box, an optional way to reply, and the
 * build it came from. Anything else has to be added deliberately, and doing so breaks the key
 * assertion below rather than shipping silently.
 */
class FeedbackPayloadTest {

    private val payload = FeedbackPayload(
        message = "The bubble disappears when I switch apps",
        contact = "lars@example.com",
        appVersion = "1.4.2 (2026090900)",
        androidSdk = 33,
        deviceModel = "OnePlus IN2023",
        createdAtMs = 1_757_000_000_000
    )

    @Test
    fun `the payload carries the message, a way to reply, and the build — nothing else`() {
        val json = JsonParser.parseString(FeedbackPayloads.encode(payload)).asJsonObject

        assertEquals(
            setOf("message", "contact", "appVersion", "androidSdk", "deviceModel", "createdAtMs"),
            json.keySet()
        )
        assertEquals(payload.message, json["message"].asString)
        assertEquals(payload.contact, json["contact"].asString)
    }

    @Test
    fun `a message survives the round trip unchanged`() {
        assertEquals(payload, FeedbackPayloads.decode(FeedbackPayloads.encode(payload)))
    }

    @Test
    fun `leaving the contact field empty sends no contact at all, not an empty one`() {
        val anonymous = payload.copy(contact = "   ")
        val json = JsonParser.parseString(FeedbackPayloads.encode(anonymous)).asJsonObject

        assertTrue("an unanswered field must not become a value", json["contact"].isJsonNull)
        assertNull(FeedbackPayloads.decode(FeedbackPayloads.encode(anonymous)).contact)
    }

    @Test
    fun `an empty box is not something to send`() {
        assertFalse(FeedbackPayloads.isSendable("   "))
        assertTrue(FeedbackPayloads.isSendable("it does not work"))
    }

    @Test
    fun `a message longer than the box allows is refused, not truncated`() {
        val tooLong = "a".repeat(FeedbackPayloads.MAX_MESSAGE_CHARS + 1)

        assertFalse("truncating would send words the person did not choose", FeedbackPayloads.isSendable(tooLong))
        assertTrue(FeedbackPayloads.isSendable("a".repeat(FeedbackPayloads.MAX_MESSAGE_CHARS)))
    }
}
