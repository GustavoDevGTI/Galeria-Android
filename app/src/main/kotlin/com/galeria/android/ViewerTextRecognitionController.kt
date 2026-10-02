package com.galeria.android

/** Owns viewer OCR requests, relevance and the two bounded text caches, not the
 * media queue, player, Android views or recognition algorithm. Entry points and
 * completions run on the main thread; workers only read isRelevant(). */
internal class ViewerTextRecognitionController(
    private val scheduler: Scheduler,
    private val recognizer: Recognizer,
    private val currentImage: () -> Image?,
    private val hostActive: () -> Boolean,
    private val listener: Listener,
    cacheCapacity: Int = 16
) {
    data class Image(val uri: String, val key: String)
    interface Scheduler {
        fun post(work: Runnable, delayMs: Long)
        fun cancel(work: Runnable)
    }
    interface Recognizer {
        fun recognize(image: Image, detailed: Boolean, isRelevant: () -> Boolean, complete: (Result<String>) -> Unit)
    }
    interface Listener {
        fun availabilityChanged(available: Boolean)
        fun manualStarted()
        fun manualCompleted(result: Result<String>, cached: Boolean)
    }

    private class TextCache(private val capacity: Int) {
        init { require(capacity > 0) }
        private val values = LinkedHashMap<String, String>(capacity, 0.75f, true)
        fun get(key: String): String? = values[key]
        fun put(key: String, text: String) {
            values[key] = text
            if (values.size > capacity) values.remove(values.keys.first())
        }
    }
    private class Request(val image: Image, val detailed: Boolean) {
        var completed = false
    }
    private val detectedText = TextCache(cacheCapacity)
    private val detailedText = TextCache(cacheCapacity)
    @Volatile private var active: Request? = null
    @Volatile private var paused = false
    @Volatile private var closed = false
    private var pending: Runnable? = null

    fun detect(image: Image?) {
        if (closed) return
        invalidate()
        listener.availabilityChanged(false)
        if (image == null || paused || !hostActive() || currentImage() != image) return
        detectedText.get(image.key)?.let {
            listener.availabilityChanged(it.isNotBlank())
            return
        }
        val request = Request(image, detailed = false)
        active = request
        lateinit var work: Runnable
        work = Runnable {
            if (pending !== work) return@Runnable
            pending = null
            start(request)
        }
        pending = work
        scheduler.post(work, AUTOMATIC_DELAY_MS)
    }

    fun requestManual(image: Image) {
        if (closed || paused || !hostActive() || currentImage() != image) return
        detailedText.get(image.key)?.takeIf(String::isNotBlank)?.let {
            listener.manualCompleted(Result.success(it), cached = true)
            return
        }
        active?.let { if (it.detailed && it.image == image) return }
        invalidate()
        val request = Request(image, detailed = true)
        active = request
        listener.manualStarted()
        start(request)
    }

    fun pause() {
        paused = true
        invalidate()
    }

    fun resume() {
        if (!closed) paused = false
    }

    fun close() {
        closed = true
        invalidate()
    }

    private fun invalidate() {
        // Clear ownership before removing queued work; old completions cannot
        // clear a newer manual request, even if the same image is opened again.
        active = null
        pending?.let(scheduler::cancel)
        pending = null
    }

    private fun isRelevant(request: Request): Boolean =
        active === request && !closed && !paused && hostActive()

    private fun start(request: Request) {
        if (!isRelevant(request)) return
        if (currentImage() != request.image) {
            active = null
            return
        }
        recognizer.recognize(request.image, request.detailed, { isRelevant(request) }) {
            complete(request, it)
        }
    }

    private fun complete(request: Request, result: Result<String>) {
        if (request.completed || !isRelevant(request)) return
        if (currentImage() != request.image) {
            active = null
            return
        }
        request.completed = true
        active = null
        result.onSuccess {
            detectedText.put(request.image.key, it)
            if (request.detailed) detailedText.put(request.image.key, it)
        }
        if (request.detailed) listener.manualCompleted(result, cached = false)
        else result.onSuccess { listener.availabilityChanged(it.isNotBlank()) }
    }

    companion object {
        private const val AUTOMATIC_DELAY_MS = 650L
    }
}
