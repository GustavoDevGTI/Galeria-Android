package com.galeria.android

import androidx.sqlite.db.SimpleSQLiteQuery

/** Sort SQL is a whitelist, never user input. Separate WHERE/ORDER BY shapes let
 * SQLite use catalog indexes; values remain bound, including search strings. */
internal object CatalogPagingQuery {
    fun build(scope: String, albumKey: String, customOrderAlbumKey: String,
        query: String, sortMode: String, descending: Boolean): SimpleSQLiteQuery {
        val args = ArrayList<Any>()
        val sql = StringBuilder("SELECT cached_media.* FROM cached_media ")
        if (sortMode == MediaSortRules.SORT_CUSTOM) {
            sql.append("LEFT JOIN custom_media_order ON custom_media_order.uri = cached_media.uri AND custom_media_order.albumKey = ? ")
            args.add(customOrderAlbumKey)
        }
        sql.append("WHERE cached_media.scope = ? ")
        args.add(scope)
        if (albumKey != "__all__") { sql.append("AND cached_media.albumKey = ? "); args.add(albumKey) }
        if (query.isNotEmpty()) {
            sql.append("AND (cached_media.name LIKE ? OR cached_media.relativePath LIKE ?) ")
            args.add("%$query%"); args.add("%$query%")
        }
        val direction = if (descending) "DESC" else "ASC"
        val order = when (sortMode) {
            MediaSortRules.SORT_CUSTOM -> "CASE WHEN custom_media_order.position IS NULL THEN 1 ELSE 0 END ASC, custom_media_order.position ASC"
            MediaSortRules.SORT_NAME -> "LOWER(cached_media.name) $direction"
            MediaSortRules.SORT_SIZE -> "cached_media.size $direction"
            MediaSortRules.SORT_DURATION -> "cached_media.duration $direction"
            MediaSortRules.SORT_TYPE -> "LOWER(cached_media.mimeType) $direction"
            else -> "cached_media.dateAdded $direction"
        }
        sql.append("ORDER BY $order, cached_media.dateAdded DESC, LOWER(cached_media.name) ASC")
        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }
}
