package com.galeria.android

import android.content.Context
import android.os.Environment
import java.io.File

/** Refresh only known albums: never discovers/reveals new hidden folders. No thumbnail decoding. */
internal object AlbumCountRefresh {
    fun refresh(context: Context, albums: List<AlbumItem>): List<AlbumItem> {
        val indexedCounts = MediaStoreRepository.currentIndexedAlbumCounts(context)
        val root = Environment.getExternalStorageDirectory()
        val classified = HiddenAlbumClassification.classify(context, albums)
        return classified.mapNotNull { album ->
            if (VirtualAlbumRules.isVirtual(album.key)) return@mapNotNull album
            val hidden = album.requiresFilesystem
            // A legacy bucket ID is not necessarily a path. Don't associate it with
            // a directory unless the catalog actually identifies it by that path.
            val count = if (!hidden && album.key == album.path && indexedCounts != null) {
                indexedCounts[album.key] ?: 0
            } else if (album.key == album.path && MediaActions.hasAllFilesAccess(context)) {
                MediaOperationNavigation.mediaCount(File(root, album.path)) ?: album.count
            } else album.count // Inaccessible metadata is unknown, never assumed empty.
            if (count == 0) null else AlbumItem(album.key, album.name, count, album.cover,
                album.latestDate, album.firstDate, album.totalSize, album.path, album.naturallyHidden, album.requiresFilesystem)
        }
    }
}
