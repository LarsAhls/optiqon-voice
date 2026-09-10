package se.optiqon.voice.domain.access

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Carries a sign-in link from the activity that received it to whatever is able to complete it.
 *
 * The activity is handed the link by the platform long before the account screen exists, and
 * the screen may be recreated before anyone acts on it, so the link is parked here rather than
 * passed down as an argument. [consume] hands it over exactly once: completing the same link
 * twice fails, and a link left lying in memory is a link that gets replayed on the next
 * rotation.
 */
@Singleton
class EmailLinkRelay @Inject constructor() {

    private val _link = MutableStateFlow<String?>(null)
    val link: StateFlow<String?> = _link.asStateFlow()

    fun offer(link: String) {
        _link.value = link
    }

    /** Returns the pending link and clears it, or null if there is none. */
    fun consume(): String? = _link.getAndUpdate { null }
}
