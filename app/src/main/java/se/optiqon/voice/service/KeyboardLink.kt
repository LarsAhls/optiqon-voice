package se.optiqon.voice.service

import java.util.concurrent.atomic.AtomicReference

/**
 * Where the accessibility service tells the bubble about the keyboard.
 *
 * One owned point, rather than the free companion fields it replaces. Two services with separate
 * lifecycles have to meet somewhere, and a process-global is the only place Android offers them;
 * what went wrong was not that the meeting point was global but that nothing owned it. Whoever
 * held a reference could write it, and `TextInjectorService.onDestroy()` did: it set the listener
 * `BubbleService.onStartCommand()` had registered back to null, leaving a live bubble deaf with no
 * code left anywhere that would register it again. That is F15.
 *
 * So the registration belongs to the listener: [listen] and [stopListening] are the bubble's, and
 * nothing the accessibility service does touches them. What the accessibility service owns is the
 * keyboard: it is the only thing that can see windows, so [accessibilityConnected] and
 * [accessibilityGone] are its to call, and both are about the keyboard rather than about who is
 * listening for it.
 */
internal class KeyboardLink {

    private val listener = AtomicReference<TextInjectorService.KeyboardListener?>(null)

    @Volatile
    var isKeyboardVisible: Boolean = false
        private set

    /** Registered by the service that owns [listener] - and only ever by it. */
    fun listen(listener: TextInjectorService.KeyboardListener) {
        this.listener.set(listener)
    }

    /**
     * Unregistered by the same service, on its way out.
     *
     * Only if it is still the registered one: a `START_STICKY` restart overlaps, so the outgoing
     * instance's `onDestroy()` can run after its replacement has already registered, and clearing
     * unconditionally there would reproduce F15 with the two services swapped.
     */
    fun stopListening(listener: TextInjectorService.KeyboardListener) {
        this.listener.compareAndSet(listener, null)
    }

    /**
     * The accessibility service is up - which, after it was turned off and on again, is the one
     * moment the keyboard's state is known and the bubble's picture of it is certainly stale.
     *
     * Stated rather than reported through [report], because there is no edge to detect: whatever
     * the listener last heard, it heard from a service that no longer exists.
     */
    fun accessibilityConnected(imeVisible: Boolean) {
        isKeyboardVisible = imeVisible
        listener.get()?.onKeyboardVisibilityChanged(imeVisible)
    }

    /**
     * The accessibility service is gone. Nothing can see the keyboard now, so it counts as down:
     * a flag left saying `visible` outlives the service that set it and no later edge can correct
     * it - the rule in [shouldReportKeyboard] is what that stale flag defeats.
     */
    fun accessibilityGone() {
        if (isKeyboardVisible) {
            isKeyboardVisible = false
            listener.get()?.onKeyboardVisibilityChanged(false)
        }
    }

    /** A measurement from the live accessibility service, filtered by [shouldReportKeyboard]. */
    fun report(imeVisible: Boolean, editableFocused: Boolean) {
        val report = shouldReportKeyboard(imeVisible, isKeyboardVisible, editableFocused)
        isKeyboardVisible = imeVisible
        if (report) listener.get()?.onKeyboardVisibilityChanged(imeVisible)
    }
}

/**
 * The one link in the process, held here rather than in either service's companion so that neither
 * service's lifecycle looks like it owns it. Tests build their own [KeyboardLink] instead.
 */
internal val keyboardLink = KeyboardLink()
