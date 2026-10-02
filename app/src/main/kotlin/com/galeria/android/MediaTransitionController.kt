package com.galeria.android

/** Main-thread-confined ownership of a committed media change, independent of
 * Android views, players and animation callbacks. Those resources stay in the
 * adapter's cleanup, which runs exactly once for each registered transition. */
internal class MediaTransitionController(private val scheduler: Scheduler) {
    interface Scheduler {
        fun schedule(completion: Runnable, delayMs: Long)
        fun cancel(completion: Runnable)
    }

    enum class State { IDLE, RESERVED, RUNNING, COMPLETING }

    var state: State = State.IDLE
        private set
    val isBusy: Boolean get() = state != State.IDLE
    val pendingCompletion: Runnable? get() = pending?.completion

    private class Pending(val cleanup: (Boolean) -> Unit, val onSettled: (Boolean) -> Unit) {
        lateinit var completion: Runnable
    }
    private var pending: Pending? = null

    /** Reserve before changing the queue or detaching the outgoing player. */
    fun begin(): Boolean {
        if (isBusy) return false
        state = State.RESERVED
        return true
    }

    fun register(durationMs: Long, cleanup: (Boolean) -> Unit, onSettled: (Boolean) -> Unit = {}): Runnable {
        check(state == State.RESERVED) { "A media transition must be reserved first" }
        require(durationMs >= 0L)
        val transition = Pending(cleanup, onSettled)
        transition.completion = Runnable {
            // An old animation/fallback must never finish a newer transition.
            if (pending === transition) finish()
        }
        pending = transition
        state = State.RUNNING
        scheduler.schedule(transition.completion, durationMs + FALLBACK_GRACE_MS)
        return transition.completion
    }

    /** Pause/save settles the destination; destruction/reload only releases
     * resources. Clear callback ownership before cleanup can cancel animators. */
    fun finish(updateUi: Boolean = true) {
        if (state == State.COMPLETING) return
        val transition = pending
        if (transition == null) {
            state = State.IDLE
            return
        }
        pending = null
        state = State.COMPLETING
        try {
            scheduler.cancel(transition.completion)
            transition.cleanup(updateUi)
        } finally {
            state = State.IDLE
        }
        transition.onSettled(updateUi)
    }

    companion object {
        private const val FALLBACK_GRACE_MS = 100L
    }
}
