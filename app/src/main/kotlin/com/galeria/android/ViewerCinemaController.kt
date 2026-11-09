package com.galeria.android

/** Main-thread owner of cinema state, saved orientation and transition callbacks.
 * Android orientation, bars, tracks and animations remain in the effects adapter. */
internal class ViewerCinemaController(
    private val scheduler: Scheduler,
    private val requestedOrientation: () -> Int,
    private val landscapeOrientation: Int,
    private val effects: Effects
) {
    interface Scheduler {
        fun post(work: Runnable, delayMs: Long)
        fun cancel(work: Runnable)
    }
    interface Effects {
        fun modeChanged(enabled: Boolean)
        fun orientationChanged(orientation: Int)
        fun barsChanged(enabled: Boolean)
        fun transitionStarted(enabled: Boolean)
        fun transitionFinished()
    }
    var enabled = false
        private set
    var automatic = false
        private set
    var previousOrientation: Int? = null
        private set
    val transitioning: Boolean get() = transition != null
    private var restoredMode: Boolean? = null
    private var restoredAutomatic = false
    private var closed = false
    private class Transition {
        var orientation: Runnable? = null
        var finish: Runnable? = null
    }
    private var transition: Transition? = null

    fun restore(mode: Boolean?, orientation: Int?, automatic: Boolean = false) {
        if (closed) return
        restoredMode = mode
        restoredAutomatic = automatic
        previousOrientation = orientation
    }

    fun open(isVideo: Boolean, albumEnabled: Boolean = false, automaticLandscape: Boolean = false) {
        if (closed) return
        settle()
        val restored = restoredMode
        restoredMode = null
        val manual = isVideo && (if (restoredAutomatic) albumEnabled else restored ?: albumEnabled)
        automatic = isVideo && !manual && automaticLandscape && (restored == null || restoredAutomatic)
        restoredAutomatic = false
        enabled = manual || automatic
        applyOrientation()
    }

    /** Follow the display while rotating automatically; only manual cinema locks landscape. */
    fun followRotation(isVideo: Boolean, automaticLandscape: Boolean, mediaTransitionBusy: Boolean) {
        if (closed || transitioning || mediaTransitionBusy || (enabled && !automatic)) return
        val next = isVideo && automaticLandscape
        if (enabled == next) return
        automatic = next
        enabled = next
        effects.modeChanged(enabled)
    }

    fun toggle(isVideo: Boolean, mediaTransitionBusy: Boolean): Boolean {
        if (closed || !isVideo || mediaTransitionBusy || transitioning) return false
        enabled = !enabled
        automatic = false
        val current = Transition()
        transition = current
        effects.modeChanged(enabled)
        if (transition !== current || closed) return true
        effects.transitionStarted(enabled)
        if (transition !== current || closed) return true
        current.orientation = Runnable {
            if (transition !== current || closed) return@Runnable
            current.orientation = null
            applyOrientation()
        }.also { scheduler.post(it, ROTATION_DELAY_MS) }
        current.finish = Runnable {
            if (transition === current && !closed) settle()
        }.also { scheduler.post(it, FALLBACK_MS) }
        return true
    }

    fun refreshBars() {
        if (!closed) effects.barsChanged(enabled)
    }

    fun settle() {
        val current = transition ?: return
        // Invalidate before effects: obsolete callbacks cannot finish a new mode.
        transition = null
        current.orientation?.let(scheduler::cancel)
        current.finish?.let(scheduler::cancel)
        if (closed) return
        if (current.orientation != null) applyOrientation()
        effects.transitionFinished()
    }

    fun close() {
        if (closed) return
        closed = true
        settle()
        restoredMode = null
    }

    private fun applyOrientation() {
        if (enabled && !automatic) {
            if (previousOrientation == null) previousOrientation = requestedOrientation()
            effects.orientationChanged(landscapeOrientation)
        } else {
            previousOrientation?.let {
                previousOrientation = null
                effects.orientationChanged(it)
            }
        }
        refreshBars()
    }

    companion object {
        const val ROTATION_DELAY_MS = 170L
        private const val FALLBACK_MS = 520L
    }
}
