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
                val cachedMedia = GalleryCatalogStore.readMedia(appContext, options.includeHidden)
                deliverAlbums(request, withVirtualAlbums(cachedSummaries, cachedMedia, options), options, onAlbums)
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
        val pinned = appContext.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
            .getStringSet(VirtualAlbumRules.PINNED_ALBUMS_PREF, emptySet()).orEmpty()
        val ordered = VirtualAlbumRules.pinEssential(sorted, pinned)
        mainHandler.post {
            if (!closed && request == generation) onAlbums(ordered, options.query)
        }
    }

    private fun buildAlbumsFromMedia(media: List<MediaItem>, options: AlbumCatalogOptions): List<AlbumItem> {
        val filteredMedia = media.filter { item ->
            MediaFilterRules.matches(item.name, item.mimeType, options.filterOptions)
        }
        return withVirtualAlbums(MediaStoreRepository.buildAlbums(filteredMedia), filteredMedia, options)
    }

    private fun withVirtualAlbums(
        sourceAlbums: List<AlbumItem>,
        media: List<MediaItem>,
        options: AlbumCatalogOptions
    ): List<AlbumItem> {
        val physical = prepareAlbums(sourceAlbums, options)
        val prefs = appContext.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val favorites = prefs
            .getStringSet("favorites", emptySet()).orEmpty()
        val trash = MediaStoreRepository.loadTrashedMedia(appContext)
        val collections = VirtualAlbumRules.addCollections(
            physical,
            media,
            favorites,
            trash,
            appContext.getString(R.string.album_recent),
            appContext.getString(R.string.album_favorites),
            appContext.getString(R.string.album_trash),
            options.hiddenKeys,
            prefs.getBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, false)
        )
        val available = VirtualAlbumRules.availableMedia(physical, media, options.hiddenKeys)
        val favoriteKeys = favorites.mapTo(HashSet(), MediaIdentityRules::canonicalKey)
        val byFolder = media.groupBy { it.albumKey }
        val customOrders = GalleryCatalogStore.allCustomOrders(appContext)
        return collections.map { album ->
            val candidates = when (album.key) {
                "all_media", VirtualAlbumRules.RECENT_KEY -> available
                VirtualAlbumRules.FAVORITES_KEY -> available.filter {
                    MediaIdentityRules.canonicalKey(it.uri.toString()) in favoriteKeys
                }
                VirtualAlbumRules.TRASH_KEY -> VirtualAlbumRules.visibleTrash(
                    trash, options.hiddenKeys,
                    prefs.getBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, false)
                )
                else -> byFolder[album.key].orEmpty()
            }
            val key = album.key
            val defaultSort = if (key == VirtualAlbumRules.RECENT_KEY || key == VirtualAlbumRules.TRASH_KEY) {
                MediaSortRules.SORT_DATE
            } else MediaSortRules.SORT_CUSTOM
            val order = customOrders[key] ?: prefs.getString("custom_order_$key", "").orEmpty()
                .lineSequence().filter { it.isNotBlank() }.toList()
            val cover = AlbumCoverRules.choose(
                candidates,
                prefs.getString(AlbumCoverRules.preferenceKey(key), null),
                AlbumMediaPreparationOptions(
                    MediaFilterOptions(
                        prefs.getBoolean(AlbumCoverRules.optionKey(key, "filter_images"), true),
                        prefs.getBoolean(AlbumCoverRules.optionKey(key, "filter_videos"), true),
                        prefs.getBoolean(AlbumCoverRules.optionKey(key, "filter_gifs"), true),
                        prefs.getBoolean(AlbumCoverRules.optionKey(key, "filter_raw"), true),
                        prefs.getBoolean(AlbumCoverRules.optionKey(key, "filter_svg"), true)
                    ),
                    prefs.getString(AlbumCoverRules.optionKey(key, "group_mode"), AlbumMediaRules.GROUP_NONE)
                        ?: AlbumMediaRules.GROUP_NONE,
                    prefs.getString(AlbumCoverRules.optionKey(key, "sort_mode"), defaultSort) ?: defaultSort,
                    prefs.getBoolean(AlbumCoverRules.optionKey(key, "sort_desc"), true)
                ),
                order
            ) ?: album.cover
            AlbumItem(album.key, album.name, album.count, cover, album.latestDate,
                album.firstDate, album.totalSize, album.path)
        }
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
