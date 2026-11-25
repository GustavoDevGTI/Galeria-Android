package com.galeria.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Environment
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
    val sortDescending: Boolean,
    val temporarilyVisibleKeys: Set<String> = emptySet(),
    val showNaturallyHidden: Boolean = includeHidden
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
    @Volatile private var generation = 0
    @Volatile private var closed = false
    // Worker-owned. Reuse the indexed base, but reconcile requested filesystem
    // folders and .nomedia every time: never cache a visibility decision.
    private var indexedMedia: List<MediaItem>? = null
    private var indexedToken = ""
    private var indexedRevision = -1L
    private var indexedAt = 0L
    private var activeScreenshotKey: String? = null
    // Main-thread copies for the screen: albums showing the pin and the standard albums among them.
    var pinnedKeys: Set<String> = emptySet()
        private set
    var essentialKeys: Set<String> = emptySet()
        private set

    fun load(
        options: AlbumCatalogOptions,
        onAlbums: (List<AlbumItem>, String) -> Unit,
        onDeferredRefreshRequired: (Boolean) -> Unit,
        onFailure: () -> Unit = {}
    ) {
        val request = ++generation
        executor.execute {
            if (closed || request != generation) return@execute
            try {
                GalleryUpgradeCoordinator.ensureReady(appContext)
                val dirty = GalleryCatalogStore.isCatalogDirty(appContext, options.includeHidden)
                val fresh = GalleryCatalogStore.hasFreshCatalog(appContext, options.includeHidden,
                    MediaActions.hasAllFilesAccess(appContext), CATALOG_FALLBACK_MAX_AGE_MS)
                if (dirty || !fresh || options.temporarilyVisibleKeys.isNotEmpty()) {
                    val media = MediaStoreRepository.queryOverviewMedia(appContext, options.includeHidden,
                        options.temporarilyVisibleKeys, options.showNaturallyHidden, indexedBase())
                    deliverAlbums(request, buildAlbumsFromMedia(media, options), options, onAlbums)
                    if (dirty || !fresh) deferRefresh(request, options, onDeferredRefreshRequired)
                    return@execute
                }
                val cachedSummaries = GalleryCatalogStore.readAlbums(appContext, options.includeHidden)
                if (cachedSummaries.isNotEmpty() && options.includesAllMediaTypes()) {
                    val cachedMedia = GalleryCatalogStore.snapshot(options.includeHidden).ifEmpty {
                        GalleryCatalogStore.readMedia(appContext, options.includeHidden)
                    }
                    deliverAlbums(request, withVirtualAlbums(cachedSummaries, cachedMedia, options), options, onAlbums)
                }

                if (cachedSummaries.isEmpty()) {
                    val media = MediaStoreRepository.queryOverviewMedia(appContext, options.includeHidden,
                        options.temporarilyVisibleKeys, options.showNaturallyHidden, indexedBase())
                    deliverAlbums(request, buildAlbumsFromMedia(media, options), options, onAlbums)
                    if (!fresh) deferRefresh(request, options, onDeferredRefreshRequired)
                    return@execute
                }

                if (!options.includesAllMediaTypes()) {
                    val media = MediaStoreRepository.loadMedia(appContext, options.includeHidden)
                    deliverAlbums(request, buildAlbumsFromMedia(media, options), options, onAlbums)
                }

                if (!fresh) {
                    mainHandler.post {
                        if (!closed && request == generation) onDeferredRefreshRequired(options.includeHidden)
                    }
                }
            } catch (error: Exception) {
                android.util.Log.e("AlbumCatalog", "Falha ao carregar catálogo", error)
                mainHandler.post { if (!closed && request == generation) onFailure() }
            }
        }
    }

    private fun indexedBase(): List<MediaItem> {
        val token = GalleryCatalogStore.mediaStoreChangeToken(appContext)
        val revision = GalleryCatalogStore.currentMutationRevision()
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = indexedMedia
        if (cached != null && token == indexedToken && revision == indexedRevision &&
            token.isNotEmpty() && now - indexedAt < 30_000L) return cached
        val source = MediaStoreRepository.queryIndexedMedia(appContext)
        indexedMedia = source
        indexedToken = token
        indexedRevision = revision
        indexedAt = now
        return source
    }

    fun refreshCatalog(
        owner: LifecycleOwner,
        includeHidden: Boolean,
        force: Boolean,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        MediaScanScheduler.enqueue(appContext, includeHidden, replace = force, onEnqueued = { workId ->
            if (closed) return@enqueue
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
        }, onFailure = { if (!closed) onFailure() })
    }

    private fun deferRefresh(request: Int, options: AlbumCatalogOptions, callback: (Boolean) -> Unit) {
        mainHandler.post {
            if (!closed && request == generation) callback(options.includeHidden)
        }
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
        val prefs = appContext.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val custom = options.sortMode == AlbumRules.SORT_CUSTOM
        AlbumRules.sort(sorted, options.sortMode, options.sortDescending,
            customOrder = AlbumReorderRules.savedOrder(prefs.getString(GalleryPreferences.ALBUM_CUSTOM_ORDER, null)))
        val pinned = prefs.getStringSet(VirtualAlbumRules.PINNED_ALBUMS_PREF, emptySet()).orEmpty()
        val unpinned = prefs.getStringSet(VirtualAlbumRules.UNPINNED_ESSENTIAL_PREF, emptySet()).orEmpty()
        val screenshotKey = activeScreenshotKey
        val ordered = VirtualAlbumRules.pinEssential(sorted, pinned,
            activeScreenshotKey = screenshotKey, unpinnedEssentialKeys = unpinned, customOrder = custom)
        val shownPinned = VirtualAlbumRules.pinnedKeys(sorted, pinned, unpinned, screenshotKey)
        val essential = sorted.filter { VirtualAlbumRules.isEssential(it, screenshotKey) }.mapTo(HashSet()) { it.key }
        mainHandler.post {
            if (!closed && request == generation) {
                pinnedKeys = shownPinned
                essentialKeys = essential
                onAlbums(ordered, options.query)
            }
        }
    }

    private fun buildAlbumsFromMedia(media: List<MediaItem>, options: AlbumCatalogOptions): List<AlbumItem> {
        val filteredMedia = media.filter { item ->
            MediaFilterRules.matches(item.name, item.mimeType, options.filterOptions)
        }
        return withVirtualAlbums(MediaStoreRepository.buildAlbums(filteredMedia), filteredMedia, options,
            classificationSource = MediaStoreRepository.buildAlbums(media))
    }

    private fun withVirtualAlbums(
        sourceAlbums: List<AlbumItem>,
        media: List<MediaItem>,
        options: AlbumCatalogOptions,
        classificationSource: List<AlbumItem> = sourceAlbums
    ): List<AlbumItem> {
        val markers = HiddenDirectoryMarkers(Environment.getExternalStorageDirectory())
        // Filters may leave fewer than eight shards. Classify the original catalog,
        // never only the folders surviving the current image/video filter.
        val naturallyHiddenKeys = AutomaticHiddenAlbums.keys(appContext, classificationSource, markers)
        // Secure folders stay in the album grid (with a lock) but never feed Recents or Favorites.
        val virtualHiddenKeys = options.hiddenKeys + naturallyHiddenKeys + SecurityStore(appContext).secureFolderKeys()
        val physical = prepareAlbums(sourceAlbums, options, naturallyHiddenKeys)
        // Decide from the unfiltered catalog: filtering one image type must not
        // promote the legacy screenshot folder as the phone's active folder.
        activeScreenshotKey = ScreenshotAlbumResolver.activeKey(appContext,
            prepareAlbums(classificationSource, options, naturallyHiddenKeys))
        val prefs = appContext.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val favorites = prefs
            .getStringSet(GalleryPreferences.FAVORITES, emptySet()).orEmpty()
        val trash = MediaStoreRepository.loadTrashedMedia(appContext)
        val hiddenTrashKeys = virtualHiddenKeys + AutomaticHiddenAlbums.keysForMedia(appContext, trash, markers)
        // Albums exposed in the grid (the eye, or hidden folders shown) show their trashed items
        // too; secure folders and albums the user hid never do.
        val secureKeys = SecurityStore(appContext).secureFolderKeys()
        val revealedTrashKeys = TemporaryAlbumVisibility.activeKeys() - secureKeys
        val lockedTrashKeys = options.hiddenKeys + secureKeys
        val collections = VirtualAlbumRules.addCollections(
            physical,
            media,
            favorites,
            trash,
            appContext.getString(R.string.album_recent),
            appContext.getString(R.string.album_favorites),
            appContext.getString(R.string.album_trash),
            hiddenTrashKeys,
            GalleryPreferences.hiddenFlag(appContext, VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF),
            keepTrash = TrashPreferences.isEnabled(appContext),
            revealedKeys = revealedTrashKeys,
            exposeNaturallyHidden = options.showNaturallyHidden,
            lockedKeys = lockedTrashKeys
        )
        val available = VirtualAlbumRules.availableMedia(physical, media, virtualHiddenKeys)
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
                    trash, hiddenTrashKeys,
                    GalleryPreferences.hiddenFlag(appContext, VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF),
                    revealedTrashKeys, options.showNaturallyHidden, lockedTrashKeys
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
                        prefs.getBoolean(AlbumCoverRules.optionKey(key, GalleryPreferences.FILTER_IMAGES), true),
                        prefs.getBoolean(AlbumCoverRules.optionKey(key, GalleryPreferences.FILTER_VIDEOS), true),
                        prefs.getBoolean(AlbumCoverRules.optionKey(key, GalleryPreferences.FILTER_GIFS), true),
                        prefs.getBoolean(AlbumCoverRules.optionKey(key, GalleryPreferences.FILTER_RAW), true),
                        prefs.getBoolean(AlbumCoverRules.optionKey(key, GalleryPreferences.ALBUM_FILTER_SVG), true)
                    ),
                    prefs.getString(AlbumCoverRules.optionKey(key, "group_mode"), AlbumMediaRules.GROUP_NONE)
                        ?: AlbumMediaRules.GROUP_NONE,
                    prefs.getString(AlbumCoverRules.optionKey(key, GalleryPreferences.SORT_MODE), defaultSort) ?: defaultSort,
                    prefs.getBoolean(AlbumCoverRules.optionKey(key, GalleryPreferences.SORT_DESC), true)
                ),
                order
            ) ?: album.cover
            val latestActivity = if (key == VirtualAlbumRules.FAVORITES_KEY) {
                maxOf(album.latestDate, MediaFavorites.latestAddition(prefs, candidates.map { it.uri.toString() }))
            } else album.latestDate
            AlbumItem(album.key, album.name, album.count, cover, latestActivity,
                album.firstDate, album.totalSize, album.path, key in naturallyHiddenKeys,
                AlbumRules.isHidden(album.path, key) || markers.containsNomedia(album.path))
        }
    }

    private fun prepareAlbums(
        source: List<AlbumItem>,
        options: AlbumCatalogOptions,
        naturallyHiddenKeys: Set<String>
    ): List<AlbumItem> {
        return AlbumCatalogRules.prepare(
            source,
            options.hiddenKeys,
            options.showNaturallyHidden,
            options.searchAllFiles,
            options.temporarilyVisibleKeys,
            naturallyHiddenKeys,
            appContext.getString(R.string.album_all_files)
        )
    }

    private companion object {
        const val CATALOG_FALLBACK_MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}
