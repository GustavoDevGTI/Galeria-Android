package com.galeria.android

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Looper
import android.os.Bundle
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import java.io.File
import java.util.Locale
import kotlin.math.absoluteValue

object MediaStoreRepository {
    private val cacheLock = Any()
    private val scanLock = Any()
    private const val CACHE_TTL_MS = 180_000L
    private var cachedVisibleMedia: ArrayList<MediaItem>? = null
    private var cachedVisibleAtMs: Long = 0L
    private var cachedHiddenMedia: ArrayList<MediaItem>? = null
    private var cachedHiddenAtMs: Long = 0L
    private var cachedWithAllFilesAccess: Boolean = false
    private var cacheInvalidated: Boolean = false

    @JvmStatic
    fun loadMedia(context: Context, includeHiddenFilesystem: Boolean = false): List<MediaItem> {
        val allFilesAccess = MediaActions.hasAllFilesAccess(context)
        val includeHidden = StorageAccessRules.includeHiddenFilesystem(includeHiddenFilesystem, allFilesAccess)
        if (GalleryCatalogStore.isCatalogDirty(context, includeHidden)) {
            return refreshMedia(context, includeHidden, force = true)
        }
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val cache = if (includeHidden) cachedHiddenMedia else cachedVisibleMedia
            val cachedAt = if (includeHidden) cachedHiddenAtMs else cachedVisibleAtMs
            if (!cacheInvalidated && cache != null && cachedWithAllFilesAccess == allFilesAccess && now - cachedAt < CACHE_TTL_MS) {
                return ArrayList(cache)
            }
        }

        if (!cacheInvalidated) {
            val memorySnapshot = GalleryCatalogStore.snapshot(includeHidden)
            if (memorySnapshot.isNotEmpty()) {
                cacheResult(memorySnapshot, includeHidden, allFilesAccess)
                return memorySnapshot
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                val stored = GalleryCatalogStore.readMedia(context.applicationContext, includeHidden)
                if (stored.isNotEmpty()) {
                    cacheResult(stored, includeHidden, allFilesAccess)
                    return stored
                }
            }
        }

        return refreshMedia(context, includeHidden)
    }

    @JvmStatic
    fun refreshMedia(
        context: Context,
        includeHiddenFilesystem: Boolean = false,
        force: Boolean = false
    ): List<MediaItem> = synchronized(scanLock) {
        val allFilesAccess = MediaActions.hasAllFilesAccess(context)
        val includeHidden = StorageAccessRules.includeHiddenFilesystem(includeHiddenFilesystem, allFilesAccess)
        if (!force && !cacheInvalidated && GalleryCatalogStore.hasFreshCatalog(
                context.applicationContext,
                includeHidden,
                allFilesAccess,
                30_000L
            )
        ) {
            val stored = GalleryCatalogStore.readMedia(context.applicationContext, includeHidden)
            cacheResult(stored, includeHidden, allFilesAccess)
            return@synchronized stored
        }
        val revision = GalleryCatalogStore.currentMutationRevision()
        val items = ArrayList<MediaItem>()
        loadFromFilesCollection(context, items)
        if (includeHidden) {
            loadFromHiddenFilesystem(items, allFilesAccess)
        }
        items.sortByDescending { it.dateAdded }
        GalleryCatalogStore.writeMedia(context.applicationContext, items, includeHidden, allFilesAccess)
        cacheResult(items, includeHidden, allFilesAccess)
        GalleryCatalogStore.clearCatalogDirty(context, includeHidden, revision)
        items
    }

    private fun cacheResult(items: List<MediaItem>, includeHiddenFilesystem: Boolean, allFilesAccess: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            if (includeHiddenFilesystem) {
                cachedHiddenMedia = ArrayList(items)
                cachedHiddenAtMs = now
            } else {
                cachedVisibleMedia = ArrayList(items)
                cachedVisibleAtMs = now
            }
            cachedWithAllFilesAccess = allFilesAccess
            cacheInvalidated = false
        }
    }

    @JvmStatic
    fun invalidateCache() {
        synchronized(cacheLock) {
            cachedVisibleMedia = null
            cachedVisibleAtMs = 0L
            cachedHiddenMedia = null
            cachedHiddenAtMs = 0L
            cacheInvalidated = true
        }
    }

    @JvmStatic
    fun buildAlbums(mediaItems: List<MediaItem>): List<AlbumItem> {
        val builders = LinkedHashMap<String, AlbumBuilder>()
        for (item in mediaItems) {
            val builder = builders.getOrPut(item.albumKey) {
                AlbumBuilder(item.albumKey, item.albumName, item.relativePath)
            }
            builder.add(item)
        }
        return builders.values.map { it.build() }
    }

    @JvmStatic
    fun loadMediaForAlbum(context: Context, albumKey: String?, includeHiddenFilesystem: Boolean = false): List<MediaItem> {
        if (albumKey == VirtualAlbumRules.TRASH_KEY) return loadTrashedMedia(context)
        val allFilesAccess = MediaActions.hasAllFilesAccess(context)
        val includeHidden = StorageAccessRules.includeHiddenFilesystem(includeHiddenFilesystem, allFilesAccess)
        if (GalleryCatalogStore.isCatalogDirty(context, includeHidden)) {
            val items = refreshMedia(context, includeHidden, force = true)
            return VirtualAlbumRules.mediaForAlbum(items, albumKey, favoriteUris(context))
        }
        if (albumKey == "all_media" || albumKey == VirtualAlbumRules.RECENT_KEY || albumKey == VirtualAlbumRules.FAVORITES_KEY) {
            return VirtualAlbumRules.mediaForAlbum(loadMedia(context, includeHidden), albumKey, favoriteUris(context))
        }
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            val cache = if (includeHidden) cachedHiddenMedia else cachedVisibleMedia
            val cachedAt = if (includeHidden) cachedHiddenAtMs else cachedVisibleAtMs
            if (cache != null && cachedWithAllFilesAccess == allFilesAccess && now - cachedAt < CACHE_TTL_MS) {
                return cache.filterTo(ArrayList()) { it.albumKey == albumKey }
            }
        }

        if (!albumKey.isNullOrEmpty() && albumKey != "root") {
            val directItems = ArrayList<MediaItem>()
            loadFromFilesCollection(context, directItems, albumKey)
            if (includeHidden) {
                val hiddenItems = ArrayList<MediaItem>()
                loadFromHiddenFilesystem(hiddenItems, allFilesAccess)
                for (item in hiddenItems) {
                    if (item.albumKey == albumKey) {
                        directItems.add(item)
                    }
                }
            }
            if (directItems.isNotEmpty()) {
                directItems.sortByDescending { it.dateAdded }
                return directItems
            }
        }

        val filtered = ArrayList<MediaItem>()
        for (item in loadMedia(context, includeHidden)) {
            if (item.albumKey == albumKey) {
                filtered.add(item)
            }
        }
        return filtered
    }

    @JvmStatic
    fun loadAlbums(context: Context, includeHiddenFilesystem: Boolean = false): List<AlbumItem> =
        buildAlbums(loadMedia(context, includeHiddenFilesystem)).sortedByDescending { it.latestDate }

    @JvmStatic
    fun loadTrashedMedia(context: Context): List<MediaItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return LegacyTrashStore.load(context)
        val items = ArrayList<MediaItem>()
        loadFromFilesCollection(context, items, trashedOnly = true)
        return items
    }

    private fun loadFromFilesCollection(
        context: Context,
        output: MutableList<MediaItem>,
        albumKey: String? = null,
        trashedOnly: Boolean = false
    ) {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.RELATIVE_PATH,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.Video.VideoColumns.DURATION,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            )
        } else {
            arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.MIME_TYPE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.BUCKET_ID,
                MediaStore.MediaColumns.BUCKET_DISPLAY_NAME,
                MediaStore.MediaColumns.DATA,
                MediaStore.Video.VideoColumns.DURATION,
                MediaStore.Files.FileColumns.MEDIA_TYPE
            )
        }

        val resolver: ContentResolver = context.contentResolver
        val selection = StringBuilder(
            "(" +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?)" +
                " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE ?" +
                " OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE ?" +
                ") AND ${MediaStore.MediaColumns.SIZE} > 0"
        )
        val args = ArrayList<String>().apply {
            add(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString())
            add(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString())
            add("image/%")
            add("video/%")
        }
        if (!albumKey.isNullOrEmpty()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection.append(" AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?")
                args.add(albumKey)
            } else {
                selection.append(" AND (${MediaStore.MediaColumns.DATA} LIKE ? OR ${MediaStore.MediaColumns.BUCKET_ID} = ?)")
                args.add("%${albumKey.trimEnd('/')}%")
                args.add(albumKey)
            }
        }
        try {
            val cursor = if (trashedOnly && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                resolver.query(
                    collection,
                    projection,
                    Bundle().apply {
                        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection.toString())
                        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args.toTypedArray())
                        putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC")
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
                    },
                    null
                )
            } else {
                resolver.query(collection, projection, selection.toString(), args.toTypedArray(), "${MediaStore.MediaColumns.DATE_ADDED} DESC")
            }
            cursor?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val mimeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val pathIndex = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                } else {
                    -1
                }
                val bucketIdIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_ID)
                val bucketNameIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                val durationIndex = cursor.getColumnIndex(MediaStore.Video.VideoColumns.DURATION)
                val dataIndex = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                } else {
                    -1
                }

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val itemUri = ContentUris.withAppendedId(collection, id)
                    val relativePath = if (pathIndex >= 0) {
                        cursor.getString(pathIndex)
                    } else {
                        pathFromData(if (dataIndex >= 0) cursor.getString(dataIndex) else null)
                    }
                    val bucketId = cursor.getString(bucketIdIndex)
                    val bucketName = cursor.getString(bucketNameIndex)
                    val albumKey = if (relativePath.isNullOrEmpty()) bucketId else relativePath
                    val albumName = cleanAlbumName(relativePath, bucketName)
                    output.add(
                        MediaItem(
                            id,
                            itemUri,
                            cursor.getString(nameIndex),
                            cursor.getString(mimeIndex),
                            cursor.getLong(dateIndex),
                            cursor.getLong(sizeIndex),
                            relativePath,
                            albumKey,
                            albumName,
                            if (durationIndex >= 0 && !cursor.isNull(durationIndex)) cursor.getLong(durationIndex) else 0L
                        )
                    )
                }
            }
        } catch (_: SecurityException) {
            // Recent Android versions may grant only photos or only videos.
        }
    }

    private fun favoriteUris(context: Context): Set<String> =
        context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
            .getStringSet("favorites", emptySet()).orEmpty()

    private fun loadFromHiddenFilesystem(output: MutableList<MediaItem>, allFilesAccess: Boolean) {
        if (!allFilesAccess) {
            return
        }
        val root = Environment.getExternalStorageDirectory()
        if (!root.exists()) {
            return
        }
        val known = HashSet<String>()
        for (item in output) {
            known.add(dedupeKey(item.relativePath, item.name, item.size))
        }
        scanDirectory(root, root, output, known, 0, false)
    }

    private fun scanDirectory(
        root: File,
        dir: File?,
        output: MutableList<MediaItem>,
        known: MutableSet<String>,
        depth: Int,
        insideHiddenArea: Boolean
    ) {
        if (dir == null || depth > 24 || shouldSkipDirectory(root, dir)) {
            return
        }
        val hiddenArea = insideHiddenArea || isHiddenMediaDirectory(dir)
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                scanDirectory(root, file, output, known, depth + 1, hiddenArea)
            } else if (hiddenArea && file.isFile && file.length() > 0 && isSupportedMediaFile(file)) {
                val relativePath = relativeFolder(root, file)
                val key = dedupeKey(relativePath, file.name, file.length())
                if (!known.add(key)) {
                    continue
                }
                val albumName = cleanAlbumName(relativePath, file.parentFile?.name ?: "Galeria")
                val mimeType = mimeFor(file)
                output.add(
                    MediaItem(
                        -file.absolutePath.hashCode().toLong().absoluteValue,
                        Uri.fromFile(file),
                        file.name,
                        mimeType,
                        maxOf(1L, file.lastModified() / 1000L),
                        file.length(),
                        relativePath,
                        if (relativePath.isEmpty()) file.parent else relativePath,
                        albumName,
                        if (mimeType.startsWith("video/")) durationForFile(file) else 0L
                    )
                )
            }
        }
    }

    private fun shouldSkipDirectory(root: File, dir: File): Boolean {
        if (dir == root) {
            return false
        }
        if (LegacyTrashStore.isTrashDirectory(dir)) return true
        val name = dir.name
        if (name == "Android") {
            return false
        }
        val parent = dir.parentFile
        if (parent != null && parent.name == "Android") {
            return name == "data" || name == "obb"
        }
        return false
    }

    private fun isHiddenMediaDirectory(dir: File): Boolean =
        dir.name.startsWith(".") || File(dir, ".nomedia").exists()

    private fun dedupeKey(relativePath: String?, name: String?, size: Long): String =
        "${relativePath.orEmpty().lowercase(Locale.US)}|${name.orEmpty().lowercase(Locale.US)}|$size"

    private fun relativeFolder(root: File, file: File): String {
        val parent = file.parentFile ?: return ""
        val rootPath = root.absolutePath
        val parentPath = parent.absolutePath
        if (parentPath.startsWith(rootPath)) {
            var relative = parentPath.substring(rootPath.length).replace('\\', '/')
            while (relative.startsWith("/")) {
                relative = relative.substring(1)
            }
            return if (relative.isEmpty()) "" else "$relative/"
        }
        return "${parent.name}/"
    }

    private fun isSupportedMediaFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        return name.endsWith(".jpg") ||
            name.endsWith(".jpeg") ||
            name.endsWith(".png") ||
            name.endsWith(".webp") ||
            name.endsWith(".gif") ||
            name.endsWith(".heic") ||
            name.endsWith(".heif") ||
            name.endsWith(".bmp") ||
            name.endsWith(".svg") ||
            name.endsWith(".dng") ||
            name.endsWith(".raw") ||
            name.endsWith(".cr2") ||
            name.endsWith(".nef") ||
            name.endsWith(".arw") ||
            name.endsWith(".orf") ||
            name.endsWith(".rw2") ||
            name.endsWith(".mp4") ||
            name.endsWith(".mkv") ||
            name.endsWith(".webm") ||
            name.endsWith(".mov") ||
            name.endsWith(".avi") ||
            name.endsWith(".3gp") ||
            name.endsWith(".m4v") ||
            name.endsWith(".ts")
    }

    private fun mimeFor(file: File): String {
        val name = file.name
        val dot = name.lastIndexOf('.')
        val ext = if (dot >= 0 && dot + 1 < name.length) name.substring(dot + 1).lowercase(Locale.US) else ""
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        if (!mime.isNullOrEmpty()) {
            return mime
        }
        if (ext == "mkv") {
            return "video/x-matroska"
        }
        if (ext == "ts") {
            return "video/mp2t"
        }
        if (ext == "svg") {
            return "image/svg+xml"
        }
        return if (isVideoExtension(ext)) "video/*" else "image/*"
    }

    private fun durationForFile(file: File): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)

    private fun isVideoExtension(ext: String): Boolean =
        ext == "mp4" ||
            ext == "mkv" ||
            ext == "webm" ||
            ext == "mov" ||
            ext == "avi" ||
            ext == "3gp" ||
            ext == "m4v" ||
            ext == "ts"

    private fun pathFromData(data: String?): String {
        if (data.isNullOrEmpty()) {
            return ""
        }
        val slash = data.lastIndexOf('/')
        if (slash <= 0) {
            return ""
        }
        val parent = data.substring(0, slash)
        val parentSlash = parent.lastIndexOf('/')
        return if (parentSlash >= 0) "${parent.substring(parentSlash + 1)}/" else "$parent/"
    }

    private fun cleanAlbumName(relativePath: String?, fallback: String?): String {
        if (!relativePath.isNullOrEmpty()) {
            var cleaned = relativePath
            if (cleaned.endsWith("/")) {
                cleaned = cleaned.substring(0, cleaned.length - 1)
            }
            val slash = cleaned.lastIndexOf('/')
            if (slash >= 0 && slash + 1 < cleaned.length) {
                cleaned = cleaned.substring(slash + 1)
            }
            if (cleaned.isNotEmpty()) {
                return cleaned
            }
        }
        return if (fallback.isNullOrEmpty()) "Galeria" else fallback
    }

    private class AlbumBuilder(
        private val key: String,
        private val name: String,
        private val path: String
    ) {
        private var count = 0
        private var cover: MediaItem? = null
        private var latestDate = 0L
        private var firstDate = Long.MAX_VALUE
        private var totalSize = 0L

        fun add(item: MediaItem) {
            count++
            totalSize += maxOf(0L, item.size)
            if (cover == null || item.dateAdded > latestDate) {
                cover = item
                latestDate = item.dateAdded
            }
            if (item.dateAdded > 0 && item.dateAdded < firstDate) {
                firstDate = item.dateAdded
            }
        }

        fun build(): AlbumItem {
            val created = if (firstDate == Long.MAX_VALUE) latestDate else firstDate
            return AlbumItem(key, name, count, cover, latestDate, created, totalSize, path)
        }
    }
}
