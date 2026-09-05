package se.optiqon.voice.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionLanguagesTest {

    @Test
    fun `quick options include Auto with no explicit code`() {
        val auto = TranscriptionLanguages.quickOptions.first { it.label == "Auto" }
        assertNull("Auto must not send an explicit ASR language code", auto.code)
    }

    @Test
    fun `quick options include Svenska mapped to the sv ISO code`() {
        val svenska = TranscriptionLanguages.quickOptions.first { it.label == "Svenska" }
        assertEquals("sv", svenska.code)
    }

    @Test
    fun `quick option codes are unique`() {
        val codes = TranscriptionLanguages.quickOptions.map { it.code }
        assertEquals("Duplicate codes would make selection ambiguous", codes.size, codes.distinct().size)
    }

    @Test
    fun `quick options are non-empty`() {
        assertTrue(TranscriptionLanguages.quickOptions.isNotEmpty())
    }
}
