package com.galeria.android

import android.content.Context
import java.io.File

/** Consulta marcadores compartilhados do Android sem alterar arquivos de outras galerias. */
internal class HiddenDirectoryMarkers(private val storageRoot: File) {
    private val markers = HashMap<String, Boolean>()

    fun containsNomedia(relativePath: String): Boolean {
        val parts = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (relativePath.startsWith('/') || parts.any { it == "." || it == ".." }) return false
        var directory = storageRoot
        if (hasMarker(directory)) return true
        for (part in parts) {
            directory = File(directory, part)
            if (hasMarker(directory)) return true
        }
        return false
    }

    fun hiddenAlbumKeys(albums: Iterable<AlbumItem>): Set<String> = albums.asSequence()
        .filter { containsNomedia(it.path) }
        .mapTo(HashSet()) { it.key }

    fun hiddenMediaKeys(media: Iterable<MediaItem>): Set<String> = media.asSequence()
        .distinctBy { it.albumKey to it.relativePath }
        .filter { containsNomedia(it.relativePath) }
        .mapTo(HashSet()) { it.albumKey }

    private fun hasMarker(directory: File): Boolean = markers.getOrPut(directory.path) {
        runCatching { File(directory, ".nomedia").isFile }.getOrDefault(false)
    }
}

/** Reconhece pastas fragmentadas por hash sem esconder álbuns pequenos comuns. */
internal object AutomaticHiddenAlbums {
    private val hexBucket = Regex("[0-9a-fA-F]{2}")
    private const val MIN_SIBLINGS = 8
    private const val MAX_ITEMS_PER_BUCKET = 3
    private const val PREFS = "gallery_automatic_hidden_albums"
    private const val PREF_PARENTS = "hash_bucket_parents"

    fun keys(
        albums: Iterable<AlbumItem>,
        markers: HiddenDirectoryMarkers,
        rememberedParents: Set<String> = emptySet()
    ): Set<String> {
        val source = albums.toList()
        val candidates = source.filter { album ->
            val path = album.path.replace('\\', '/').trimEnd('/')
            val leaf = path.substringAfterLast('/')
            album.count in 1..MAX_ITEMS_PER_BUCKET && path.contains('/') && hexBucket.matches(leaf)
        }
        val generated = candidates.groupBy(::parentPath)
            .filter { (parent, siblings) ->
                parent in rememberedParents || siblings.map { it.path.lowercase() }.distinct().size >= MIN_SIBLINGS
            }.values
            .flatMapTo(HashSet()) { siblings -> siblings.map { it.key } }
        generated.addAll(markers.hiddenAlbumKeys(source))
        return generated
    }

    fun keys(context: Context, albums: Iterable<AlbumItem>, markers: HiddenDirectoryMarkers): Set<String> {
        val source = albums.toList()
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val remembered = prefs.getStringSet(PREF_PARENTS, emptySet()).orEmpty()
            val detected = source.filter {
                val leaf = it.path.replace('\\', '/').trimEnd('/').substringAfterLast('/')
                it.count in 1..MAX_ITEMS_PER_BUCKET && it.path.contains('/') && hexBucket.matches(leaf)
            }.groupBy(::parentPath).filter { (_, siblings) ->
                siblings.map { it.path.lowercase() }.distinct().size >= MIN_SIBLINGS
            }.keys
            val parents = remembered + detected
            if (parents != remembered) prefs.edit().putStringSet(PREF_PARENTS, parents).apply()
            return keys(source, markers, parents)
        }
    }

    fun keysForMedia(context: Context, media: Iterable<MediaItem>, markers: HiddenDirectoryMarkers): Set<String> =
        keys(context, MediaStoreRepository.buildAlbums(media.toList()), markers)

    private fun parentPath(album: AlbumItem): String =
        album.path.replace('\\', '/').trimEnd('/').substringBeforeLast('/')
}
