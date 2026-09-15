package com.galeria.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.Executors

data class AlbumCatalogOptions(
    val includeHidden: Boolean,
    val searchAllFiles: Boolean,
    val hiddenKeys: Set<String>,
    val query: String,
    val filterOptions: MediaFilterOptions,
    val sortMode: String,
    val sortDescending: Boolean
) {
    fun includesAllMediaTypes(): Boolean =
        (filterOptions.showImages || filterOptions.showPortraits) &&
            filterOptions.showVideos &&
            filterOptions.showGifs &&
            filterOptions.showRaw &&
            filterOptions.showSvgs
}

class AlbumCatalogController(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generation = 0
    @Volatile private var closed = false

    fun load(
        options: AlbumCatalogOptions,
        onAlbums: (List<AlbumItem>, String) -> Unit,
        onDeferredRefreshRequired: (Boolean) -> Unit
    ) {
        val request = ++generation
        executor.execute {
            if (GalleryCatalogStore.isCatalogDirty(appContext, options.includeHidden)) {
                val media = MediaStoreRepository.refreshMedia(appContext, options.includeHidden, force = true)
                deliverAlbums(request, buildAlbumsFromMedia(media, options), options, onAlbums)
                return@execute
            }
            val cachedSummaries = GalleryCatalogStore.readAlbums(appContext, options.includeHidden)
            if (cachedSummaries.isNotEmpty() && options.includesAllMediaTypes()) {
                deliverAlbums(request, prepareAlbums(cachedSummaries, options), options, onAlbums)
            }

            if (cachedSummaries.isEmpty()) {
                val media = MediaStoreRepository.refreshMedia(appContext, options.includeHidden, force = true)
                deliverAlbums(request, buildAlbumsFromMedia(media, options), options, onAlbums)
                return@execute
            }

            if (!options.includesAllMediaTypes()) {
                val media = MediaStoreRepository.loadMedia(appContext, options.includeHidden)
                deliverAlbums(request, buildAlbumsFromMedia(media, options), options, onAlbums)
            }

            val fresh = GalleryCatalogStore.hasFreshCatalog(
                appContext,
                options.includeHidden,
                MediaActions.hasAllFilesAccess(appContext),
                CATALOG_FALLBACK_MAX_AGE_MS
            )
            if (!fresh) {
                mainHandler.post {
                    if (!closed && request == generation) onDeferredRefreshRequired(options.includeHidden)
                }
            }
        }
    }

    fun refreshCatalog(
        owner: LifecycleOwner,
        includeHidden: Boolean,
        force: Boolean,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val workId = MediaScanScheduler.enqueue(appContext, includeHidden, replace = force)
        val workInfo = WorkManager.getInstance(appContext).getWorkInfoByIdLiveData(workId)
        val observer = object : Observer<WorkInfo?> {
            override fun onChanged(value: WorkInfo?) {
                value ?: return
                if (!value.state.isFinished) return
                workInfo.removeObserver(this)
                if (closed) return
                if (value.state == WorkInfo.State.SUCCEEDED) onSuccess() else onFailure()
            }
        }
        workInfo.observe(owner, observer)
    }

    fun close() {
        closed = true
        generation++
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }

    private fun deliverAlbums(
        request: Int,
        albums: List<AlbumItem>,
        options: AlbumCatalogOptions,
        onAlbums: (List<AlbumItem>, String) -> Unit
    ) {
        val sorted = albums.toMutableList()
        AlbumRules.sort(sorted, options.sortMode, options.sortDescending)
        mainHandler.post {
            if (!closed && request == generation) onAlbums(sorted, options.query)
        }
    }

    private fun buildAlbumsFromMedia(media: List<MediaItem>, options: AlbumCatalogOptions): List<AlbumItem> {
        val filteredMedia = media.filter { item ->
            MediaFilterRules.matches(item.name, item.mimeType, options.filterOptions)
        }
        return prepareAlbums(MediaStoreRepository.buildAlbums(filteredMedia), options)
    }

    private fun prepareAlbums(source: List<AlbumItem>, options: AlbumCatalogOptions): List<AlbumItem> {
        return AlbumCatalogRules.prepare(
            source,
            options.hiddenKeys,
            options.includeHidden,
            options.searchAllFiles
        )
    }

    private companion object {
        const val CATALOG_FALLBACK_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
