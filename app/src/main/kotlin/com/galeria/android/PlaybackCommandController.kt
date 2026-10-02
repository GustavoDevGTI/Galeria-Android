package com.galeria.android

/** Main-thread command authority. Owns command/gesture identity, never creates or
 * releases a player. Channels expire when their owning playback session detaches. */
internal class PlaybackCommandController(private val scheduler: Scheduler) {
    interface Scheduler {
        fun post(work: Runnable, delayMs: Long)
        fun cancel(work: Runnable)
    }
    interface Port {
        val position: Long
        val duration: Long
        val isPlaying: Boolean
        val playWhenReady: Boolean
        val ended: Boolean
        fun play()
        fun pause()
        fun seek(position: Long)
        /** Returns restoration of the previous seek mode, not a default mode. */
        fun exactSeek(): () -> Unit
    }
    inner class Channel internal constructor(internal val port: Port) {
        val active: Boolean get() = available(this)
        val desiredPlayback: Boolean get() = if (current === this) lifecyclePlayback ?: scrub?.resume ?: port.playWhenReady else false
        fun play() {
            if (!available(this)) return
            abandonScrub()
            if (port.ended) port.seek(0L)
            port.play()
        }
        fun pause() {
            if (current !== this || closed) return
            abandonScrub()
            if (suspended) lifecyclePlayback = false
            port.pause()
        }
        fun toggle() {
            if (!available(this)) return
            if (scrub?.resume ?: port.isPlaying) pause() else play()
        }
        fun seek(position: Long) {
            if (!available(this)) return
            abandonScrub()
            port.seek(position.coerceIn(0L, port.duration.takeIf { it > 0L } ?: Long.MAX_VALUE))
        }
        fun seekBy(delta: Long) {
            if (available(this)) seek(DetailPlaybackRules.seekTarget(port.position, delta, port.duration))
        }
        fun seekProgress(progress: Int) {
            if (available(this) && port.duration > 0L) seek(port.duration * progress.coerceIn(0, 1000) / 1000L)
        }
        fun beginScrub(): Scrub? {
            if (!available(this)) return null
            scrub?.let { return it }
            val gesture = Scrub(this, port.position, port.playWhenReady, port.exactSeek())
            scrub = gesture
            port.pause()
            return gesture
        }
    }
    inner class Scrub internal constructor(
        internal val channel: Channel,
        internal val before: Long,
        internal val resume: Boolean,
        internal val restoreSeek: () -> Unit
    ) {
        internal var pending: Long? = null
        internal var work: Runnable? = null
        val active: Boolean get() = scrub === this && available(channel)
        fun move(position: Long) {
            if (!active) return
            pending = position.coerceAtLeast(0L)
            if (work != null) return
            val task = Runnable {
                if (!active) return@Runnable
                work = null
                val target = pending
                pending = null
                target?.let(channel.port::seek)
            }
            work = task
            scheduler.post(task, 60L)
        }
        fun finish(position: Long, cancelled: Boolean, allowResume: Boolean = true) {
            if (!active) return
            val port = channel.port
            abandonScrub(restore = false)
            try {
                port.seek(if (cancelled) before else position.coerceIn(0L, (port.duration - 1L).coerceAtLeast(0L)))
            } finally { restoreSeek() }
            if (allowResume && resume && available(channel)) port.play()
        }
    }
    private var current: Channel? = null
    private var scrub: Scrub? = null
    private var suspended = false
    private var lifecyclePlayback: Boolean? = null
    private var closed = false

    fun bind(port: Port): Channel {
        check(!closed) { "Playback command authority is closed" }
        detach()
        return Channel(port).also { current = it; if (suspended) port.pause() }
    }
    fun suspend() {
        if (closed || suspended) return
        lifecyclePlayback = current?.desiredPlayback
        suspended = true // Revoke play before any gesture/seek callback is cancelled.
        val gesture = scrub
        abandonScrub(restore = false)
        gesture?.let {
            try { it.channel.port.seek(it.before) } finally { it.restoreSeek() }
        }
        current?.port?.pause()
    }
    fun resume(restorePlayback: Boolean) {
        if (closed) return
        val wasSuspended = suspended
        suspended = false
        val wanted = lifecyclePlayback
        lifecyclePlayback = null
        if (wasSuspended && restorePlayback && wanted == true) current?.play()
    }
    fun detach() {
        // Cancel work and restore seek parameters before the owner releases it.
        abandonScrub()
        current = null
        lifecyclePlayback = null
    }
    fun close() { if (!closed) { detach(); closed = true } }
    private fun available(channel: Channel) = !closed && !suspended && current === channel
    private fun abandonScrub(restore: Boolean = true) {
        val gesture = scrub ?: return
        scrub = null
        gesture.work?.let(scheduler::cancel)
        gesture.work = null
        gesture.pending = null
        if (restore) gesture.restoreSeek()
    }
}
