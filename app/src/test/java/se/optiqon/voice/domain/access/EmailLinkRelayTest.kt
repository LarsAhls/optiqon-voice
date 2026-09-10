package se.optiqon.voice.domain.access

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The relay exists to hand a sign-in link over exactly once. A link that survives being read
 * is a link that gets completed a second time on the next rotation, and the second attempt
 * fails — leaving the tester looking at an error for a sign-in that already worked.
 */
class EmailLinkRelayTest {

    @Test
    fun `a link is delivered once and then gone`() {
        val relay = EmailLinkRelay()
        relay.offer("https://voice.optiqon.se/signin?oobCode=abc")

        assertEquals("https://voice.optiqon.se/signin?oobCode=abc", relay.consume())
        assertNull(relay.consume())
    }

    @Test
    fun `there is nothing to consume until a link arrives`() {
        assertNull(EmailLinkRelay().consume())
    }

    @Test
    fun `a newer link replaces one nobody got round to`() {
        val relay = EmailLinkRelay()
        relay.offer("https://voice.optiqon.se/signin?oobCode=old")
        relay.offer("https://voice.optiqon.se/signin?oobCode=new")

        // The older code is the stale one: the tester is acting on the link they just opened.
        assertEquals("https://voice.optiqon.se/signin?oobCode=new", relay.consume())
    }

    @Test
    fun `the observable value tracks what is waiting`() {
        val relay = EmailLinkRelay()
        assertNull(relay.link.value)

        relay.offer("https://voice.optiqon.se/signin?oobCode=abc")
        assertEquals("https://voice.optiqon.se/signin?oobCode=abc", relay.link.value)

        relay.consume()
        assertNull(relay.link.value)
    }
}
