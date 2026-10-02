package com.galeria.android

/** Selection is independent of Android; preparation/filtering stays with the album. */
internal object AlbumCoverSelection {
    fun <T> choose(
        media: List<T>, manualUri: String?, uri: (T) -> String,
        prepare: (List<T>, Boolean) -> List<T>
    ): T? {
        if (media.isEmpty()) return null
        if (manualUri != null) media.firstOrNull {
            MediaIdentityRules.sameUri(uri(it), manualUri)
        }?.let { return it }
        return prepare(media, false).firstOrNull() ?: prepare(media, true).firstOrNull()
    }
}
