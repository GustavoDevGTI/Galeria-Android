package com.galeria.android

import android.content.Context
import android.os.Environment

/** Run on a worker. UI binding must never inspect .nomedia or directory contents. */
internal object HiddenAlbumClassification {
    fun classify(context: Context, albums: List<AlbumItem>): List<AlbumItem> {
        val markers = HiddenDirectoryMarkers(Environment.getExternalStorageDirectory())
        val keys = AutomaticHiddenAlbums.keys(context, albums, markers)
        return albums.map { album ->
            val filesystem = AlbumRules.isHidden(album.path, album.key) || markers.containsNomedia(album.path)
            AlbumItem(album.key, album.name, album.count, album.cover, album.latestDate,
                album.firstDate, album.totalSize, album.path, filesystem || album.key in keys, filesystem)
        }
    }
}
