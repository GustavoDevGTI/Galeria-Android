package com.galeria.android

/** Main-thread detection state; worker relevance never reads the gallery queue. */
internal class ViewerMotionPhotoController(
    private val detector: Detector,
    private val currentSource: () -> Source?,
    private val hostActive: () -> Boolean,
    private val changed: (MotionPhotoClip?) -> Unit
) {
    data class Source(val uri: String, val key: String)
    interface Detector {
        fun detect(source: Source, isRelevant: () -> Boolean, complete: (MotionPhotoClip?) -> Unit)
    }
    private class Request(val source: Source)
    private val cache = HashMap<String, MotionPhotoClip?>()
    @Volatile private var active: Request? = null
    @Volatile private var paused = false
    @Volatile private var closed = false
    private var selected: Source? = null
    private var clip: MotionPhotoClip? = null

    fun select(source: Source?) {
        if (closed) return
        active = null
        selected = source
        clip = null
        changed(null)
        if (source == null || paused || !hostActive() || currentSource() != source) return
        if (cache.containsKey(source.key)) {
            clip = cache[source.key]
            changed(clip)
            return
        }
        val request = Request(source)
        active = request
        detector.detect(source, { relevant(request) }) { result ->
            if (!relevant(request)) return@detect
            if (currentSource() != source) { active = null; return@detect }
            active = null
            cache[source.key] = result
            clip = result
            changed(result)
        }
    }

    fun clipFor(source: Source?): MotionPhotoClip? =
        if (!closed && !paused && hostActive() && source != null && source == selected && currentSource() == source) clip else null

    fun pause() { paused = true; active = null }
    fun resume() { if (!closed) paused = false }
    fun close() { closed = true; active = null; selected = null; clip = null; cache.clear() }
    private fun relevant(request: Request) = active === request && !paused && !closed && hostActive()
}

/** Owns the embedded-video session, not the gallery queue or its video player.
 * All callbacks and lifecycle entry points are delivered on the main thread. */
internal class MotionPhotoPlaybackSession<F, P : Any>(
    private val hostActive: () -> Boolean,
    private val create: (F) -> P,
    private val bindAndPrepare: (P, F) -> Unit,
    private val play: (P) -> Unit,
    private val pause: (P) -> Unit,
    private val release: (P) -> Unit,
    private val resumeUpdates: () -> Unit,
    private val suspendUpdates: () -> Unit,
    private val unbind: () -> Unit,
    private val failed: (Throwable) -> Unit
) {
    var player: P? = null
        private set
    private var started = false
    private var closed = false
    private var requested = false
    private var awaiting = false

    fun load(extract: ((Result<F>) -> Unit) -> Unit) {
        if (closed || requested) return
        requested = true
        awaiting = true
        extract { result ->
            if (closed || !awaiting) return@extract
            awaiting = false
            if (!hostActive()) return@extract
            result.fold(onSuccess = { file ->
                val created = create(file)
                if (closed || !hostActive()) { release(created); return@fold }
                player = created
                bindAndPrepare(created, file)
                if (closed || player !== created) return@fold
                if (started) play(created) else suspendUpdates()
            }, onFailure = failed)
        }
    }

    fun start() {
        if (closed) return
        started = true
        // Preserve the existing lifecycle: returning does not force a paused
        // Motion Photo to play again. Only a newly prepared active session plays.
        resumeUpdates()
    }

    fun stop() {
        if (closed) return
        started = false
        suspendUpdates()
        player?.let(pause)
    }

    fun close() {
        if (closed) return
        closed = true
        awaiting = false
        val owned = player
        player = null
        try { unbind() } finally { owned?.let(release) }
    }
}
