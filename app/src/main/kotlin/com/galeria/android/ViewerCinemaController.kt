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
    var previousOrientation: Int? = null
        private set
    val transitioning: Boolean get() = transition != null
    private var restoredMode: Boolean? = null
    private var closed = false
    private class Transition {
        var orientation: Runnable? = null
        var finish: Runnable? = null
    }
    private var transition: Transition? = null

    fun restore(mode: Boolean?, orientation: Int?) {
        if (closed) return
        restoredMode = mode
        previousOrientation = orientation
    }

    fun open(isVideo: Boolean, albumEnabled: Boolean = false) {
        if (closed) return
        settle()
        val restored = restoredMode
        restoredMode = null
        enabled = isVideo && (restored ?: albumEnabled)
        applyOrientation()
    }

    fun toggle(isVideo: Boolean, mediaTransitionBusy: Boolean): Boolean {
        if (closed || !isVideo || mediaTransitionBusy || transitioning) return false
        enabled = !enabled
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
        if (enabled) {
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
