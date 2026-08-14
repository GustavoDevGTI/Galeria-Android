package com.galeria.android

import android.net.Uri

class MediaItem(
    @JvmField val id: Long,
    @JvmField val uri: Uri,
    name: String?,
    mimeType: String?,
    @JvmField val dateAdded: Long,
    @JvmField val size: Long,
    relativePath: String?,
    albumKey: String?,
    albumName: String?,
    @JvmField val duration: Long = 0L
) {
    @JvmField val name: String = name ?: "Sem nome"
    @JvmField val mimeType: String = mimeType ?: ""
    @JvmField val relativePath: String = relativePath ?: ""
    @JvmField val albumKey: String = if (albumKey.isNullOrEmpty()) "root" else albumKey
    @JvmField val albumName: String = if (albumName.isNullOrEmpty()) "Galeria" else albumName

    fun isVideo(): Boolean = mimeType.startsWith("video/")

    fun isImage(): Boolean = mimeType.startsWith("image/")
}
