package se.optiqon.voice.data.db.entity

import se.optiqon.voice.domain.model.Profile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProfileEntityTest {

    private fun profile(language: String?) = Profile(name = "Test", language = language)

    @Test
    fun `explicit Swedish language survives save and load as sv`() {
        val entity = profile("sv").toEntity()
        assertEquals("sv", entity.language)
        assertEquals("sv", entity.toDomain().language)
    }

    @Test
    fun `Auto language stays null through save and load`() {
        val entity = profile(null).toEntity()
        assertNull("Auto must not persist an explicit language code", entity.language)
        assertNull(entity.toDomain().language)
    }

    @Test
    fun `blank language is normalized to null (Auto) on save`() {
        val entity = profile("   ").toEntity()
        assertNull(entity.language)
    }

    @Test
    fun `language is lowercased and trimmed on save`() {
        val entity = profile(" SV ").toEntity()
        assertEquals("sv", entity.language)
    }
}
