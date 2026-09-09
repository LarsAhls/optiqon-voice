package se.optiqon.voice.debug

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import se.optiqon.voice.data.db.entity.Dictation
import se.optiqon.voice.data.db.entity.ProfileEntity
import se.optiqon.voice.data.db.entity.TextReplacementRuleEntity
import se.optiqon.voice.data.storage.StorageRoot
import java.io.File

/**
 * The B.2 seed/dump hook (Mission L1) against an in-memory stand-in for the stores. The real
 * Android sink is exercised by `scripts/signing/rotation-e2e.sh` on an emulator; this test pins
 * the contract that script relies on: what gets seeded, when seeding refuses, and that the
 * dump is deterministic and never contains the key material.
 */
class SyntheticStateTest {

    @get:Rule val folder = TemporaryFolder()

    private class FakeSink(override var processRootName: String = StorageRoot.DEFAULT.name) : SyntheticState.Sink {
        val profiles = mutableListOf<ProfileEntity>()
        val rules = mutableListOf<TextReplacementRuleEntity>()
        val dictations = mutableListOf<Dictation>()
        var asrKey = ""
        var llmKey = ""
        var owner: String? = null
        var activeUid: String? = null
        var access: String? = null
        var settingsSeeded = false

        override suspend fun insertProfile(profile: ProfileEntity) { profiles += profile }
        override suspend fun insertRule(rule: TextReplacementRuleEntity) { rules += rule }
        override suspend fun insertDictation(dictation: Dictation) { dictations += dictation }
        override suspend fun seedSettings(asrApiKey: String, llmApiKey: String) {
            asrKey = asrApiKey; llmKey = llmApiKey; settingsSeeded = true
        }
        override fun claimDefaultAndActivate(uid: String) {
            check(owner == null) { "The data on this device is already claimed" }
            owner = uid; activeUid = uid
        }
        override suspend fun recordApproved(uid: String) { access = "APPROVED" }
        var lastRootRead: StorageRoot? = null

        override suspend fun readRoot(root: StorageRoot) = SyntheticState.DefaultRootState(
            dumpedRoot = root.name,
            processRoot = processRootName,
            dbUserVersion = 8,
            profileNames = profiles.map { it.name },
            activeProfileNames = profiles.filter { it.isActive }.map { it.name },
            ruleNames = rules.map { it.name },
            dictationCount = dictations.size,
            dictationWordTotal = dictations.sumOf { it.wordCount },
            dictationTexts = dictations.map { it.text },
            settings = if (settingsSeeded) mapOf("onboarding_complete" to "true", "silence_threshold_ms" to "3210") else emptyMap(),
            asrApiKey = asrKey,
            llmApiKey = llmKey,
            defaultOwner = owner,
            activeUid = activeUid,
            accessStatus = access
        ).also { lastRootRead = root }
    }

    private fun stateFile() = File(folder.root, "debug/${SyntheticState.STATE_FILE_NAME}")

    private fun stateFile(root: String) = File(folder.root, "debug/state-$root.txt")

    @Test
    fun `seed writes the fixed data set, claims the default root and records approval`() = runTest {
        val sink = FakeSink()
        val outcome = SyntheticState(sink, folder.root).seed()

        assertTrue(outcome.description, outcome.ok)
        assertEquals(2, sink.profiles.size)
        assertEquals(listOf("Synthetic Alpha"), sink.profiles.filter { it.isActive }.map { it.name })
        assertEquals(3, sink.rules.size)
        assertTrue(sink.rules.any { it.isRegex })
        assertEquals(5, sink.dictations.size)
        assertTrue(sink.settingsSeeded)
        assertEquals(SyntheticState.ASR_API_KEY, sink.asrKey)
        assertEquals(SyntheticState.LLM_API_KEY, sink.llmKey)
        assertEquals(SyntheticState.UID, sink.owner)
        assertEquals(SyntheticState.UID, sink.activeUid)
        assertEquals("APPROVED", sink.access)
    }

    @Test
    fun `seed refuses to run twice and refuses on a non-default process root`() = runTest {
        val sink = FakeSink()
        val state = SyntheticState(sink, folder.root)
        assertTrue(state.seed().ok)

        val second = state.seed()
        assertFalse(second.ok)
        assertEquals("nothing may be written on the second run", 2, sink.profiles.size)

        val other = FakeSink(processRootName = StorageRoot.SIGNED_OUT.name)
        val refused = SyntheticState(other, folder.root).seed()
        assertFalse(refused.ok)
        assertTrue(other.profiles.isEmpty())
        assertEquals(null, other.owner)
    }

    @Test
    fun `seed refuses when the default root already belongs to someone`() = runTest {
        val sink = FakeSink().apply { owner = "someone-else" }
        val outcome = SyntheticState(sink, folder.root).seed()
        assertFalse(outcome.ok)
        assertTrue(sink.profiles.isEmpty())
        assertEquals("someone-else", sink.owner)
    }

    @Test
    fun `dump is deterministic and carries key digests, never key values`() = runTest {
        val sink = FakeSink()
        val state = SyntheticState(sink, folder.root)
        state.seed()

        assertTrue(state.dump().ok)
        val first = stateFile().readText()
        assertTrue(state.dump().ok)
        val second = stateFile().readText()

        assertEquals(first, second)
        assertFalse(first.contains(SyntheticState.ASR_API_KEY))
        assertFalse(first.contains(SyntheticState.LLM_API_KEY))
        assertTrue(first.contains("asr_api_key_sha256=" + SyntheticState.sha256(SyntheticState.ASR_API_KEY)))
        assertTrue(first.contains("llm_api_key_sha256=" + SyntheticState.sha256(SyntheticState.LLM_API_KEY)))
        assertTrue(first.contains("profiles=2\n"))
        assertTrue(first.contains("rules=3\n"))
        assertTrue(first.contains("dictations=5\n"))
        assertTrue(first.contains("default_owner=${SyntheticState.UID}\n"))
        assertTrue(first.contains("access_status=APPROVED\n"))
        assertTrue(first.contains("setting.silence_threshold_ms=3210\n"))
    }

    @Test
    fun `dump reflects a change in the stored data`() = runTest {
        val sink = FakeSink()
        val state = SyntheticState(sink, folder.root)
        state.seed()
        state.dump()
        val before = stateFile().readText()

        sink.asrKey = ""          // what a lost keystore master key would look like: empty read-back
        sink.dictations.removeAt(0)
        state.dump()
        val after = stateFile().readText()

        assertFalse(before == after)
        assertTrue(after.contains("asr_api_key_len=0\n"))
        assertTrue(after.contains("dictations=4\n"))
    }

    @Test
    fun `receiver commands route the two new actions`() = runTest {
        val sink = FakeSink()
        val commands = AccessDebugCommands(AccessDebugControls(), folder.root, { null }, SyntheticState(sink, folder.root))

        assertTrue(commands.handle(AccessDebugCommands.ACTION_SEED_SYNTHETIC, emptyMap()).ok)
        assertEquals(2, sink.profiles.size)
        assertTrue(commands.handle(AccessDebugCommands.ACTION_DUMP_STATE, emptyMap()).ok)
        assertTrue(stateFile().exists())

        val unwired = AccessDebugCommands(AccessDebugControls(), folder.root, { null })
        assertFalse(unwired.handle(AccessDebugCommands.ACTION_SEED_SYNTHETIC, emptyMap()).ok)
    }

    @Test
    fun `a dump with no root named describes the root this process is on`() = runTest {
        val sink = FakeSink(processRootName = "u1")

        assertTrue(SyntheticState(sink, folder.root).dump().ok)

        assertEquals(StorageRoot("u1"), sink.lastRootRead)
        assertTrue(stateFile("u1").readText().contains("root=u1\n"))
        assertFalse("the default root's file must not be overwritten by another root's dump", stateFile().exists())
    }

    @Test
    fun `each root's evidence is written to a file of its own`() = runTest {
        val sink = FakeSink(processRootName = "u1")
        val state = SyntheticState(sink, folder.root)

        assertTrue(state.dump(StorageRoot.DEFAULT).ok)
        assertTrue(state.dump(StorageRoot("u2")).ok)

        assertTrue(stateFile().readText().contains("root=default\n"))
        assertTrue(stateFile("u2").readText().contains("root=u2\n"))
        assertTrue("the root read is not the root the process is on", stateFile("u2").readText().contains("# process_root=u1"))
    }

    @Test
    fun `an unusable root name is refused rather than guessed at`() = runTest {
        val sink = FakeSink()
        val commands = AccessDebugCommands(AccessDebugControls(), folder.root, { null }, SyntheticState(sink, folder.root))

        val outcome = commands.handle(
            AccessDebugCommands.ACTION_DUMP_STATE,
            mapOf(AccessDebugCommands.EXTRA_ROOT to "../other-app")
        )

        assertFalse(outcome.ok)
        assertEquals("nothing may be read", null, sink.lastRootRead)
    }

    @Test
    fun `the root extra names the root to read`() = runTest {
        val sink = FakeSink()
        val commands = AccessDebugCommands(AccessDebugControls(), folder.root, { null }, SyntheticState(sink, folder.root))

        assertTrue(commands.handle(
            AccessDebugCommands.ACTION_DUMP_STATE,
            mapOf(AccessDebugCommands.EXTRA_ROOT to "U2")
        ).ok)

        assertEquals(StorageRoot("u2"), sink.lastRootRead)
    }
}
