package com.galeria.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Environment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.Observer
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

data class AlbumMediaCatalogOptions(
    val albumKey: String?,
    val includeHidden: Boolean,
    val query: String,
    val filterOptions: MediaFilterOptions,
    val groupMode: String,
    val sortMode: String,
    val sortDescending: Boolean,
    val selectionMode: Boolean
)

class AlbumMediaCatalogController(context: Context) {
    private val appContext = context.applicationContext
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var generation = 0
    private var pagingJob: Job? = null
    @Volatile private var closed = false

    fun load(
        scope: CoroutineScope,
        options: AlbumMediaCatalogOptions,
        onItems: (List<MediaItem>) -> Unit,
        onPage: suspend (PagingData<MediaItem>) -> Unit
    ) {
        if (closed) return
        val request = ++generation
        pagingJob?.cancel()
        if (AlbumMediaRules.shouldUsePaging(options.albumKey, options.groupMode, options.selectionMode)) {
            val userHiddenKeys = appContext.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
                .getStringSet("hidden_folder_keys", emptySet()).orEmpty().toSet()
            val aggregateAlbum = options.albumKey == null || options.albumKey == "all_media" ||
                options.albumKey == VirtualAlbumRules.RECENT_KEY
            pagingJob = scope.launch {
                if (MediaStoreRepository.isPhysicalAlbum(options.albumKey)) {
                    withContext(Dispatchers.IO) {
                        val key = options.albumKey!!
                        if (options.includeHidden || GalleryCatalogStore.isCatalogDirty(appContext, options.includeHidden) ||
                            !GalleryCatalogStore.hasAlbumMedia(appContext, options.includeHidden, key) ||
                            !GalleryCatalogStore.hasFreshCatalog(appContext, options.includeHidden,
                                MediaActions.hasAllFilesAccess(appContext), 180_000L)) {
                            MediaStoreRepository.refreshAlbumMedia(appContext, key, options.includeHidden)
                        }
                    }
                } else if (GalleryCatalogStore.isCatalogDirty(appContext, options.includeHidden)) {
                    withContext(Dispatchers.IO) {
                        MediaStoreRepository.refreshMedia(appContext, options.includeHidden, force = true)
                    }
                }
                val hiddenKeys = userHiddenKeys + if (aggregateAlbum) {
                    withContext(Dispatchers.IO) {
                        AutomaticHiddenAlbums.keys(
                            appContext,
                            GalleryCatalogStore.readAlbums(appContext, options.includeHidden),
                            HiddenDirectoryMarkers(Environment.getExternalStorageDirectory())
                        )
                    }
                } else emptySet()
                GalleryCatalogStore.pagedMedia(
                    appContext,
                    options.includeHidden,
                    options.albumKey,
                    options.query,
                    options.sortMode,
                    options.sortDescending,
                    PagingConfig(
                        pageSize = 30,
                        initialLoadSize = 45,
                        prefetchDistance = 12,
                        enablePlaceholders = true,
                        maxSize = 180
                    )
                ).map { page ->
                    page.filter { item ->
                        MediaFilterRules.matches(item.name, item.mimeType, options.filterOptions) &&
                            (!aggregateAlbum || !VirtualAlbumRules.isHiddenMedia(item, hiddenKeys))
                    }
                }.collectLatest { page ->
                    if (!closed && request == generation) onPage(page)
                }
            }
            return
        }

        executor.execute {
            if (closed || request != generation) return@execute
            val physicalAlbum = MediaStoreRepository.isPhysicalAlbum(options.albumKey)
            if (!physicalAlbum && GalleryCatalogStore.isCatalogDirty(appContext, options.includeHidden)) {
                MediaStoreRepository.refreshMedia(appContext, options.includeHidden, force = true)
            }
            val cachedAlbum = if (GalleryCatalogStore.isCatalogDirty(appContext, options.includeHidden) ||
                (physicalAlbum && !GalleryCatalogStore.hasFreshCatalog(appContext, options.includeHidden,
                    MediaActions.hasAllFilesAccess(appContext), 180_000L))) {
                emptyList()
            } else {
                options.albumKey
                    ?.takeIf { it.isNotEmpty() && it != "all_media" && it != "root" }
                    ?.let { GalleryCatalogStore.readAlbumMedia(appContext, options.includeHidden, it) }
                    .orEmpty()
            }
            val source = if (cachedAlbum.isNotEmpty() && !options.includeHidden) {
                cachedAlbum
            } else if (physicalAlbum) {
                MediaStoreRepository.queryAlbumMedia(appContext, options.albumKey!!, options.includeHidden)
            } else if (cachedAlbum.isNotEmpty()) {
                cachedAlbum
            } else {
                MediaStoreRepository.loadMediaForAlbum(appContext, options.albumKey, options.includeHidden)
            }
            val customOrder = GalleryCatalogStore.migrateLegacyOrder(appContext, options.albumKey ?: "all")
            val items = AlbumMediaRules.prepare(
                source,
                AlbumMediaPreparationOptions(
                    options.filterOptions,
                    options.groupMode,
                    options.sortMode,
                    options.sortDescending
                ),
                customOrder
            )
            mainHandler.post {
                if (!closed && request == generation) onItems(items)
            }
        }
    }

    fun refreshCatalog(
        owner: LifecycleOwner,
        includeHidden: Boolean,
        onComplete: (Boolean) -> Unit
    ) {
        MediaScanScheduler.enqueue(appContext, includeHidden, replace = true, onEnqueued = { workId ->
            if (closed) return@enqueue
            val workInfo = WorkManager.getInstance(appContext).getWorkInfoByIdLiveData(workId)
            val observer = object : Observer<WorkInfo?> {
                override fun onChanged(value: WorkInfo?) {
                    value ?: return
                    if (!value.state.isFinished) return
                    workInfo.removeObserver(this)
                    if (!closed) onComplete(value.state == WorkInfo.State.SUCCEEDED)
                }
            }
            workInfo.observe(owner, observer)
        }, onFailure = { if (!closed) onComplete(false) })
    }

    fun saveCustomOrder(albumKey: String?, order: List<MediaItem>, onSaved: () -> Unit) {
        executor.execute {
            GalleryCatalogStore.saveCustomOrder(appContext, albumKey ?: "all", order)
            mainHandler.post {
                if (!closed) onSaved()
            }
        }
    }

    fun close() {
        closed = true
        generation++
        pagingJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        executor.shutdownNow()
    }
}
