package com.galeria.android

/** A capa manual só vale enquanto a mídia ainda pertencer à coleção exibida. */
object AlbumCoverRules {
    fun preferenceKey(albumKey: String): String = "album_cover_$albumKey"

    fun optionKey(albumKey: String?, suffix: String): String =
        "album_${suffix}_${albumKey?.hashCode() ?: "all"}"

    fun choose(
        media: List<MediaItem>,
        manualUri: String?,
        options: AlbumMediaPreparationOptions,
        customOrder: List<String>
    ): MediaItem? {
        if (media.isEmpty()) return null
        if (manualUri != null) media.firstOrNull {
            MediaIdentityRules.sameUri(it.uri.toString(), manualUri)
        }?.let { return it }
        return AlbumMediaRules.prepare(media, options, customOrder).firstOrNull()
            ?: AlbumMediaRules.prepare(
                media,
                options.copy(filterOptions = MediaFilterOptions(true, true, true, true, true)),
                customOrder
            ).firstOrNull()
    }
}
