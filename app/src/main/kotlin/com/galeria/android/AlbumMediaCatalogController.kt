package com.galeria.android

import android.content.Context
import android.os.Handler
import android.os.Looper
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
            pagingJob = scope.launch {
                if (GalleryCatalogStore.isCatalogDirty(appContext, options.includeHidden)) {
                    withContext(Dispatchers.IO) {
                        MediaStoreRepository.refreshMedia(appContext, options.includeHidden, force = true)
                    }
                }
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
                        MediaFilterRules.matches(item.name, item.mimeType, options.filterOptions)
                    }
                }.collectLatest { page ->
                    if (!closed && request == generation) onPage(page)
                }
            }
            return
        }

        executor.execute {
            if (GalleryCatalogStore.isCatalogDirty(appContext, options.includeHidden)) {
                MediaStoreRepository.refreshMedia(appContext, options.includeHidden, force = true)
            }
            val cachedAlbum = if (GalleryCatalogStore.isCatalogDirty(appContext, options.includeHidden)) {
                emptyList()
            } else {
                options.albumKey
                    ?.takeIf { it.isNotEmpty() && it != "all_media" && it != "root" }
                    ?.let { GalleryCatalogStore.readAlbumMedia(appContext, options.includeHidden, it) }
                    .orEmpty()
            }
            val source = if (cachedAlbum.isNotEmpty()) {
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
        val workId = MediaScanScheduler.enqueue(appContext, includeHidden, replace = true)
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
