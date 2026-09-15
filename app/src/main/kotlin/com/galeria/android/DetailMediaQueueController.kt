package com.galeria.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

class DetailMediaQueueController(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val queue = ArrayList<MediaItem>()
    private var generation = 0
    @Volatile private var closed = false

    val items: List<MediaItem> get() = queue
    var currentIndex: Int = 0
        private set
    var shuffleMode: Boolean = false
    var presentationMode: Boolean = false
    var shuffleSeed: Long = 0L

    fun configureModes(shuffle: Boolean, presentation: Boolean, seed: Long) {
        shuffleMode = shuffle
        presentationMode = presentation
        shuffleSeed = seed
    }

    fun setInitial(item: MediaItem) {
        queue.clear()
        queue.add(item)
        currentIndex = 0
    }

    fun loadAlbum(
        albumKey: String?,
        includeHiddenFilesystem: Boolean,
        currentUri: Uri,
        onLoaded: () -> Unit
    ) {
        if (albumKey.isNullOrEmpty()) return
        val request = ++generation
        executor.execute {
            val loaded = MediaStoreRepository.loadMediaForAlbum(appContext, albumKey, includeHiddenFilesystem)
            val ordered = applyCustomOrder(loaded, albumKey)
            val available = if (presentationMode) ordered.filter { it.isImage() } else ordered
            if (available.isEmpty()) return@execute
            val arranged = if (shuffleMode) shuffledFromCurrent(available, currentUri, shuffleSeed) else available
            val selectedIndex = if (shuffleMode) {
                0
            } else {
                arranged.indexOfFirst {
                    MediaIdentityRules.sameUri(it.uri.toString(), currentUri.toString())
                }.takeIf { it >= 0 } ?: 0
            }
            mainHandler.post {
                if (closed || request != generation) return@post
                queue.clear()
                queue.addAll(arranged)
                currentIndex = selectedIndex
                onLoaded()
            }
        }
    }

    fun advance(direction: Int): Int {
        currentIndex = ViewerStateRules.advancedIndex(currentIndex, direction, queue.size)
        return currentIndex
    }

    fun setCurrentIndex(index: Int) {
        currentIndex = ViewerStateRules.wrappedIndex(index, queue.size)
    }

    fun retainImages(currentUri: Uri) {
        val images = queue.filter { it.isImage() }
        queue.clear()
        queue.addAll(images)
        currentIndex = queue.indexOfFirst { it.uri == currentUri }.takeIf { it >= 0 } ?: 0
    }

    fun replace(item: MediaItem, replacement: MediaItem): Boolean {
        val index = queue.indexOfFirst { it.uri == item.uri }
        if (index < 0) return false
        queue[index] = replacement
        return index == currentIndex
    }

    fun removeCurrent(): Boolean {
        generation++ // A load started before the mutation must not restore the removed item.
        if (queue.isEmpty()) return false
        queue.removeAt(currentIndex)
        val nextIndex = ViewerStateRules.indexAfterRemoval(currentIndex, queue.size) ?: return false
        currentIndex = nextIndex
        return true
    }

    fun current(): MediaItem = queue[currentIndex]

    fun close() {
        closed = true
        generation++
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    private fun applyCustomOrder(items: List<MediaItem>, albumKey: String): List<MediaItem> {
        val saved = GalleryCatalogStore.migrateLegacyOrder(appContext, albumKey)
        if (saved.isEmpty()) return items
        val byUri = items.associateBy { it.uri.toString() }
        val ordered = ArrayList<MediaItem>()
        val used = HashSet<String>()
        for (uri in ViewerStateRules.orderedUris(byUri.keys.toList(), saved)) {
            byUri[uri]?.let(ordered::add)
            used.add(uri)
        }
        items.filterTo(ordered) { !used.contains(it.uri.toString()) }
        return ordered
    }

    private fun shuffledFromCurrent(items: List<MediaItem>, currentUri: Uri, seed: Long): List<MediaItem> {
        val byUri = items.associateBy { it.uri.toString() }
        return ViewerStateRules.shuffledFromCurrent(byUri.keys.toList(), currentUri.toString(), seed)
            .mapNotNull(byUri::get)
    }
}
