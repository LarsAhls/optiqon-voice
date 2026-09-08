package se.optiqon.voice.testing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import se.optiqon.voice.data.access.AccessStateStore
import se.optiqon.voice.domain.access.AccessConfig
import se.optiqon.voice.domain.access.AccessGuard
import se.optiqon.voice.domain.access.AccessRefresher
import se.optiqon.voice.domain.access.AccessRepository
import se.optiqon.voice.domain.access.AccountStatus
import se.optiqon.voice.domain.access.ActiveIdentity
import se.optiqon.voice.domain.access.AuthGateway
import se.optiqon.voice.domain.access.Clock
import se.optiqon.voice.domain.access.RefreshOutcome
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The access layer wired up with everything Firebase replaced by something a JVM test can move
 * around: clocks that can be pushed forward, an auth gateway that can sign somebody out
 * mid-sentence, and a server that answers whatever the test tells it to.
 *
 * Assembled once and shared, because every negative test in this area needs the same five
 * pieces and needs them wired the same way the app wires them. A test that builds its own
 * slightly different graph proves something about that graph, not about the app.
 *
 * Pass a [TestScope.backgroundScope], not the test scope itself. `AccessRepository.decision`
 * shares eagerly and DataStore keeps a collector open, so neither coroutine ever finishes;
 * handing them the test's own scope makes `runTest` wait for work that by design never ends.
 */
class AccessFixture(
    context: Context,
    scope: CoroutineScope,
    graceMs: Long = 72L * 60L * 60L * 1000L
) {
    val clock = MovableClock()
    val auth = FakeAuthGateway()
    val activeIdentity = ActiveIdentity()
    val store = MemoryAccessStateStore(context, scope)
    val server = ScriptedRegistrationReader()

    val repository = AccessRepository(
        authGateway = auth,
        accessStateStore = store,
        activeIdentity = activeIdentity,
        clock = clock,
        config = AccessConfig(graceMs = graceMs),
        scope = scope
    )

    val refresher = AccessRefresher(
        registrationRepository = { server },
        accessRepository = repository,
        activeIdentity = activeIdentity,
        clock = clock,
        scope = scope
    )

    val guard = AccessGuard(repository, activeIdentity, refresher)

    init {
        server.activeIdentity = activeIdentity
        server.accessRepository = repository
    }

    /** Signs [uid] in the way the app does: auth first, then a fresh epoch. */
    fun signIn(uid: String, email: String = "$uid@example.test") {
        auth.signIn(uid, email)
        activeIdentity.update(uid)
    }

    fun signOut() {
        auth.clear()
        activeIdentity.update(null)
    }

    /** Stores a server verdict as of now, the only way a snapshot is ever legitimately made. */
    suspend fun recordServerVerdict(status: AccountStatus): Boolean {
        val epoch = activeIdentity.current.value ?: return false
        return repository.record(epoch, activeIdentity.nextSeq(), status)
    }
}

/** Both of the gate's clocks, independently movable, so a lying wall clock can be simulated. */
class MovableClock(
    var wall: Long = 1_800_000_000_000L,
    var elapsed: Long = 10_000_000L
) : Clock {
    override fun wallMs(): Long = wall
    override fun elapsedMs(): Long = elapsed

    /** Moves both clocks forward together, as real time does. */
    fun advance(ms: Long) {
        wall += ms
        elapsed += ms
    }
}

class FakeAuthGateway : AuthGateway {
    private val uids = MutableStateFlow<String?>(null)
    override var currentEmail: String? = null
        private set
    override var isEmailVerified: Boolean = true
    var reloads: Int = 0
        private set

    override val currentUid: String? get() = uids.value
    override fun uidChanges(): Flow<String?> = uids.asStateFlow()

    fun signIn(uid: String, email: String) {
        currentEmail = email
        uids.value = uid
    }

    override suspend fun reload() {
        reloads++
    }

    override suspend fun signOut() = clear()

    /** The same effect without the suspension, for tests that sign out mid-assertion. */
    fun clear() {
        currentEmail = null
        uids.value = null
    }
}

/**
 * A real [AccessStateStore] on a private file, so that ordering between tests cannot decide an
 * outcome and the persistence being exercised is the app's own.
 */
class MemoryAccessStateStore(
    context: Context,
    scope: CoroutineScope
) : AccessStateStore(context) {
    override val store: DataStore<Preferences> = PreferenceDataStoreFactory.create(scope = scope) {
        File(context.cacheDir, "access-${COUNTER.incrementAndGet()}.preferences_pb")
    }

    private companion object {
        val COUNTER = AtomicInteger()
    }
}

/**
 * The server, as far as the refresher can tell — including the part that matters most.
 *
 * It mirrors `RegistrationRepository.refresh()` exactly where the ordering lives: the epoch and
 * the sequence number are taken *before* the read, and a confirmed answer is recorded through
 * `AccessRepository.record`, which is what decides whether an overtaken answer takes effect.
 * [answer] is suspending so a test can hold a read open across a sign-out and let it land
 * afterwards, which is the shape of the late-answer problem.
 */
class ScriptedRegistrationReader : AccessRefresher.RegistrationReader {
    var answer: suspend () -> RefreshOutcome = { RefreshOutcome.NoNetwork }
    var reads: Int = 0
        private set

    internal lateinit var activeIdentity: ActiveIdentity
    internal lateinit var accessRepository: AccessRepository

    override suspend fun refresh(): RefreshOutcome {
        reads++
        val epoch = activeIdentity.current.value ?: return RefreshOutcome.NoAccount
        val seq = activeIdentity.nextSeq()
        val outcome = answer()
        if (outcome is RefreshOutcome.Confirmed) {
            accessRepository.record(epoch, seq, outcome.status)
        } else {
            accessRepository.invalidate()
        }
        return outcome
    }
}
