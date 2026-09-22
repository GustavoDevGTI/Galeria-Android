package com.galeria.android

import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import java.util.concurrent.Executors

data class AlbumSelectionResult(
    val completed: Int,
    val requested: Int,
    val completedItems: List<MediaItem> = emptyList(),
    val emptiedAlbumKeys: Set<String> = emptySet()
)

class AlbumSelectionActions(
    private val activity: Activity,
    private val prefs: SharedPreferences
) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    fun shareIntent(selected: List<MediaItem>): Intent? {
        if (selected.isEmpty()) return null
        return Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(selected.map { it.uri }))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun addToFavorites(selected: List<MediaItem>): Int {
        if (selected.isEmpty()) return 0
        val favorites = HashSet(prefs.getStringSet("favorites", emptySet()).orEmpty())
        favorites.addAll(selected.map { it.uri.toString() })
        prefs.edit().putStringSet("favorites", favorites).apply()
        return selected.size
    }

    fun delete(selected: List<MediaItem>, requestCode: Int): AlbumSelectionResult {
        val completed = selected.filter { item ->
            MediaActions.requestDelete(activity, item.uri, requestCode) == MediaActions.RESULT_DONE
        }
        return AlbumSelectionResult(completed.size, selected.size, completed)
    }

    fun permanentlyDelete(selected: List<MediaItem>, requestCode: Int): AlbumSelectionResult {
        val completed = selected.filter { item ->
            MediaActions.requestPermanentDelete(activity, item.uri, requestCode) == MediaActions.RESULT_DONE
        }
        return AlbumSelectionResult(completed.size, selected.size, completed)
    }

    fun restore(selected: List<MediaItem>, requestCode: Int): AlbumSelectionResult {
        val completed = selected.filter { item ->
            MediaActions.requestRestore(activity, item.uri, requestCode) == MediaActions.RESULT_DONE
        }
        return AlbumSelectionResult(completed.size, selected.size, completed)
    }

    fun move(selected: List<MediaItem>, folder: String): AlbumSelectionResult {
        val sourceFolders = selected.associate { item ->
            item.albumKey to MediaActions.fileFromMediaStore(activity, item.uri)?.parentFile
        }
        val completed = selected.filter { item ->
            MediaActions.moveToFolder(activity, item, folder) == MediaActions.RESULT_DONE
        }
        val emptied = sourceFolders.filter { (key, folder) ->
            completed.any { it.albumKey == key } && MediaOperationNavigation.isEmptyFolder(folder)
        }.keys
        return AlbumSelectionResult(completed.size, selected.size, completed, emptied)
    }

    fun loadMoveTargets(
        exposedKeys: List<String>?,
        hiddenKeys: Set<String>,
        excludedAlbumKey: String?,
        includeHidden: Boolean,
        onTargets: (List<AlbumItem>) -> Unit
    ) {
        if (closed) return
        executor.execute {
            val source = MediaStoreRepository.loadAlbums(activity.applicationContext, includeHidden)
            val targets = AlbumTargetRules.orderedTargets(
                source,
                exposedKeys,
                hiddenKeys,
                setOfNotNull(excludedAlbumKey),
                prefs.getString("sort_mode", AlbumRules.SORT_MODIFIED) ?: AlbumRules.SORT_MODIFIED,
                prefs.getBoolean("sort_desc", true)
            )
            activity.runOnUiThread {
                if (!closed && !activity.isFinishing && !activity.isDestroyed) onTargets(targets)
            }
        }
    }

    fun close() {
        closed = true
        executor.shutdownNow()
    }
}
