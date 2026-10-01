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
        val parents = rememberedParents.mapTo(HashSet(), ::normalizedPath) + detectedParents(source)
        // The small-bucket threshold is evidence for the initial classification,
        // not a visibility rule. A known cache must stay hidden as it grows,
        // is partially indexed, or adds directories inside its hash buckets.
        val generated = source.filter { album ->
            val path = normalizedPath(album.path)
            parents.any { parent ->
                val prefix = "$parent/"
                path.startsWith(prefix) && hexBucket.matches(path.removePrefix(prefix).substringBefore('/'))
            }
        }.mapTo(HashSet()) { it.key }
        generated.addAll(markers.hiddenAlbumKeys(source))
        return generated
    }

    fun keys(context: Context, albums: Iterable<AlbumItem>, markers: HiddenDirectoryMarkers): Set<String> {
        val source = albums.toList()
        synchronized(this) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val remembered = prefs.getStringSet(PREF_PARENTS, emptySet()).orEmpty()
            val detected = detectedParents(source)
            val parents = remembered + detected
            if (parents != remembered) prefs.edit().putStringSet(PREF_PARENTS, parents).apply()
            return keys(source, markers, parents)
        }
    }

    fun keysForMedia(context: Context, media: Iterable<MediaItem>, markers: HiddenDirectoryMarkers): Set<String> =
        keys(context, MediaStoreRepository.buildAlbums(media.toList()), markers)

    private fun detectedParents(source: List<AlbumItem>): Set<String> = source.filter {
        val path = normalizedPath(it.path)
        it.count in 1..MAX_ITEMS_PER_BUCKET && path.contains('/') &&
            hexBucket.matches(path.substringAfterLast('/'))
    }.groupBy { normalizedPath(it.path).substringBeforeLast('/') }
        .filter { (_, siblings) -> siblings.map { normalizedPath(it.path) }.distinct().size >= MIN_SIBLINGS }
        .keys

    private fun normalizedPath(path: String): String = path.replace('\\', '/').trimEnd('/')
}
