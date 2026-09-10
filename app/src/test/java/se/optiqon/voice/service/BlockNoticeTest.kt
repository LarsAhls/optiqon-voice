package se.optiqon.voice.service

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowToast
import se.optiqon.voice.domain.access.BlockReason

/**
 * A blocked dictation has to say why (F14).
 *
 * The reason was already resolved to a sentence and handed to the bubble, but the bubble
 * draws icons: a red pill with a retry arrow and an X. The words existed and reached
 * nobody. What this pins is the pairing — the sentence the bubble is given is the same
 * sentence the person is told, for every reason there is, and no two reasons read alike.
 */
@RunWith(RobolectricTestRunner::class)
class BlockNoticeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `every block reason is spoken aloud, not only drawn`() {
        val shown = BlockReason.values().associateWith { reason ->
            ShadowToast.reset()
            val announced = announceBlock(context, reason)
            assertEquals(
                "the toast must carry the same words the bubble state carries",
                announced,
                ShadowToast.getTextOfLatestToast()
            )
            announced
        }

        shown.forEach { (reason, text) ->
            assertTrue("$reason must say something", text.isNotBlank())
        }
        assertEquals(
            "two reasons that read alike leave the person with the same non-answer",
            shown.size,
            shown.values.toSet().size
        )
    }
}
