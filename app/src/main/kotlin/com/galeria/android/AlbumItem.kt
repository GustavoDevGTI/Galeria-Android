package com.galeria.android

class AlbumItem(
    @JvmField val key: String,
    @JvmField val name: String,
    @JvmField val count: Int,
    @JvmField val cover: MediaItem?,
    @JvmField val latestDate: Long,
    @JvmField val firstDate: Long,
    @JvmField val totalSize: Long,
    path: String?,
    @JvmField val naturallyHidden: Boolean = false,
    @JvmField val requiresFilesystem: Boolean = false
) {
    @JvmField val path: String = path ?: ""
}
