package se.optiqon.voice.debug

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.optiqon.voice.domain.access.AccessRefresher
import se.optiqon.voice.domain.access.RefreshOutcome
import java.io.File

/**
 * The debug-only smoke hooks (Mission L1, A.1). This test lives in `src/testDebug` because
 * the classes it exercises exist only in the debug source set; the release unit-test task
 * neither compiles nor sees it.
 */
class AccessDebugHooksTest {

    @get:Rule val folder = TemporaryFolder()

    private val controls = AccessDebugControls()

    private class CountingReader : AccessRefresher.RegistrationReader {
        var calls = 0
        override suspend fun refresh(): RefreshOutcome {
            calls++
            return RefreshOutcome.Throttled
        }
    }

    @Test
    fun `defaults leave clock and refresh untouched`() = runTest {
        val clock = DebugClock(controls, wall = { 1_000L }, elapsed = { 50L })
        assertEquals(1_000L, clock.wallMs())
        assertEquals(50L, clock.elapsedMs())

        val delegate = CountingReader()
        val reader = DebugRegistrationReader(delegate, controls)
        assertEquals(RefreshOutcome.Throttled, reader.refresh())
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `clock offset shifts wall and elapsed time by the same amount`() {
        val clock = DebugClock(controls, wall = { 1_000L }, elapsed = { 50L })
        controls.clockOffsetMs = 72L * 60 * 60 * 1000
        assertEquals(1_000L + 259_200_000L, clock.wallMs())
        assertEquals(50L + 259_200_000L, clock.elapsedMs())
        controls.clockOffsetMs = 0
        assertEquals(1_000L, clock.wallMs())
    }

    @Test
    fun `forced failure short-circuits the real reader and is reversible`() = runTest {
        val delegate = CountingReader()
        val reader = DebugRegistrationReader(delegate, controls)

        controls.failRefresh = true
        val outcome = reader.refresh()
        assertTrue(outcome is RefreshOutcome.Failed)
        assertEquals("debug: forced refresh failure", (outcome as RefreshOutcome.Failed).cause?.message)
        assertEquals("the real reader must not be contacted while failure is forced", 0, delegate.calls)

        controls.failRefresh = false
        assertEquals(RefreshOutcome.Throttled, reader.refresh())
        assertEquals(1, delegate.calls)
    }

    @Test
    fun `broadcast commands flip the controls and reject malformed input`() = runTest {
        val commands = AccessDebugCommands(controls, folder.root, idToken = { null })

        assertTrue(commands.handle(AccessDebugCommands.ACTION_FAIL_REFRESH, mapOf("enabled" to true)).ok)
        assertTrue(controls.failRefresh)
        assertTrue(commands.handle(AccessDebugCommands.ACTION_FAIL_REFRESH, mapOf("enabled" to false)).ok)
        assertFalse(controls.failRefresh)
        assertFalse(commands.handle(AccessDebugCommands.ACTION_FAIL_REFRESH, emptyMap()).ok)

        assertTrue(commands.handle(AccessDebugCommands.ACTION_CLOCK_OFFSET, mapOf("offsetMs" to 5_000L)).ok)
        assertEquals(5_000L, controls.clockOffsetMs)
        assertFalse(commands.handle(AccessDebugCommands.ACTION_CLOCK_OFFSET, mapOf("offsetMs" to "soon")).ok)
        assertEquals(5_000L, controls.clockOffsetMs)

        assertFalse(commands.handle("se.optiqon.voice.debug.NOPE", emptyMap()).ok)
    }

    @Test
    fun `token export writes to the private debug file and never echoes the token`() = runTest {
        val secret = "header.payload.signature-not-a-real-token"
        val commands = AccessDebugCommands(controls, folder.root, idToken = { secret })

        val outcome = commands.handle(AccessDebugCommands.ACTION_EXPORT_ID_TOKEN, emptyMap())

        assertTrue(outcome.ok)
        assertFalse("the description must not contain the token", outcome.description.contains(secret))
        assertEquals(secret, File(folder.root, "debug/${AccessDebugCommands.TOKEN_FILE_NAME}").readText())
    }

    @Test
    fun `token export without a signed-in user writes nothing`() = runTest {
        val commands = AccessDebugCommands(controls, folder.root, idToken = { null })

        val outcome = commands.handle(AccessDebugCommands.ACTION_EXPORT_ID_TOKEN, emptyMap())

        assertFalse(outcome.ok)
        assertFalse(File(folder.root, "debug/${AccessDebugCommands.TOKEN_FILE_NAME}").exists())
    }
}
