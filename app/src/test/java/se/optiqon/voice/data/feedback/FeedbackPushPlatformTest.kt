package se.optiqon.voice.data.feedback

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import se.optiqon.voice.domain.feedback.FeedbackReplyNotice

/** S6 on the device: the installation id, and the one notification Feedback can show. */
@RunWith(RobolectricTestRunner::class)
class FeedbackPushPlatformTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @Test
    fun `the installation id is stable across reads and instances, and fits the rules`() {
        val file = context.getSharedPreferences("install-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        val first = InstallationId(file).get()

        assertEquals(first, InstallationId(file).get())
        assertEquals(first, InstallationId(file).get())
        assertTrue(first.length <= TokenDocuments.MAX_INSTALLATION_ID)
        assertTrue(first.matches(Regex("[0-9a-f-]{36}")))

        val other = context.getSharedPreferences("install-test-${System.nanoTime()}", Context.MODE_PRIVATE)
        assertNotEquals(first, InstallationId(other).get())
    }

    @Test
    fun `the notification says only the fixed title and body`() {
        val n = SystemNoticePoster.build(context, null)
        val extras = n.extras

        assertEquals(FeedbackReplyNotice.TITLE, extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(FeedbackReplyNotice.BODY, extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(null, extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertEquals(null, extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertEquals(NotificationCompat.VISIBILITY_PUBLIC, n.visibility)
        assertEquals(FeedbackReplyNotice.CHANNEL_ID, n.channelId)
    }

    @Test
    fun `posting shows one notice, opening the app with no case in the intent`() {
        SystemNoticePoster(context) { true }.post()
        SystemNoticePoster(context) { true }.post()

        val shown = shadowOf(manager).allNotifications
        assertEquals("one slot, replaced", 1, shown.size)
        val intent = shadowOf(shown.single().contentIntent).savedIntent
        assertTrue(intent.extras == null || intent.extras!!.isEmpty)
        assertEquals(null, intent.data)
    }

    @Test
    fun `without permission nothing is posted`() {
        SystemNoticePoster(context) { false }.post()
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `the token document carries exactly the keys the rules allow`() {
        assertEquals(setOf("token", "platform", "updatedAt"), TokenDocuments.registration("t", "now").keys)
        assertEquals("android", TokenDocuments.registration("t", "now")["platform"])
    }

    @Test
    fun `the read marker carries exactly the keys the rules allow and only moves forward`() {
        assertEquals(setOf("seenPublicRev", "readAt"), ReadMarkers.marker(3L, "now").keys)
        assertTrue(ReadMarkers.movesForward(null, 1L))
        assertTrue(ReadMarkers.movesForward(2L, 3L))
        assertFalse(ReadMarkers.movesForward(3L, 3L))
        assertFalse(ReadMarkers.movesForward(4L, 3L))
        assertFalse("nothing public happened, nothing to write", ReadMarkers.movesForward(null, 0L))
    }

    @Test
    fun `a provider failure is told without the provider's words`() {
        val denied = ReadMarkers.neutral(com.google.firebase.firestore.FirebaseFirestoreException.Code.PERMISSION_DENIED)
        assertEquals(se.optiqon.voice.domain.feedback.RemoteResult.Denied("Refused."), denied)
    }
}
