package com.galeria.android

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.provider.MediaStore
import java.io.File
import java.util.concurrent.Executors

class DetailMediaActions(
    private val activity: Activity,
    private val prefs: SharedPreferences
) {
    private val executor = Executors.newSingleThreadExecutor()
    @Volatile private var closed = false

    fun shareIntent(item: MediaItem): Intent = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType.ifEmpty { "*/*" }
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun openWithIntent(item: MediaItem): Intent {
        val mediaLabel = if (item.isVideo()) "vídeo" else "imagem"
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mimeType.ifEmpty { if (item.isVideo()) "video/*" else "image/*" })
            clipData = ClipData.newRawUri(mediaLabel, item.uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun toggleFavorite(item: MediaItem): Boolean {
        val favorites = HashSet(prefs.getStringSet("favorites", emptySet()).orEmpty())
        val key = item.uri.toString()
        val favorite = if (favorites.remove(key)) {
            false
        } else {
            favorites.add(key)
            true
        }
        prefs.edit().putStringSet("favorites", favorites).apply()
        return favorite
    }

    fun isFavorite(item: MediaItem): Boolean =
        prefs.getStringSet("favorites", emptySet()).orEmpty().contains(item.uri.toString())

    @Throws(SecurityException::class)
    fun rename(item: MediaItem, newName: String): Boolean =
        activity.contentResolver.update(
            item.uri,
            ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, newName) },
            null,
            null
        ) > 0

    fun renamedItem(item: MediaItem, newName: String): MediaItem = MediaItem(
        item.id,
        item.uri,
        newName,
        item.mimeType,
        item.dateAdded,
        item.size,
        item.relativePath,
        item.albumKey,
        item.albumName,
        item.duration
    )

    fun delete(item: MediaItem, requestCode: Int): Int =
        MediaActions.requestPermanentDelete(activity, item.uri, requestCode)

    fun copyToHidden(item: MediaItem): File? = MediaActions.copyToHidden(activity, item)

    fun copyToFolder(item: MediaItem, folder: String): Int =
        MediaActions.copyToFolder(activity, item, folder)

    fun moveToFolder(item: MediaItem, folder: String): Int =
        MediaActions.moveToFolder(activity, item, folder)

    fun loadTargets(
        exposedKeys: List<String>?,
        hiddenKeys: Set<String>,
        excludedKeys: Set<String>,
        includeHidden: Boolean,
        onTargets: (List<AlbumItem>) -> Unit
    ) {
        if (closed) return
        executor.execute {
            val targets = AlbumTargetRules.orderedTargets(
                MediaStoreRepository.loadAlbums(activity.applicationContext, includeHidden),
                exposedKeys,
                hiddenKeys,
                excludedKeys,
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
