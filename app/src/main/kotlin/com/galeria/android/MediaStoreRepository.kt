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
    @Volatile private var completedVisibleScans = 0L
    @Volatile private var completedHiddenScans = 0L

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
    ): List<MediaItem> {
        val requestHidden = StorageAccessRules.includeHiddenFilesystem(
            includeHiddenFilesystem, MediaActions.hasAllFilesAccess(context))
        val completedAtRequest = if (requestHidden) completedHiddenScans else completedVisibleScans
        return synchronized(scanLock) {
            val allFilesAccess = MediaActions.hasAllFilesAccess(context)
            val includeHidden = StorageAccessRules.includeHiddenFilesystem(includeHiddenFilesystem, allFilesAccess)
            val alreadyCompleted = (if (includeHidden) completedHiddenScans else completedVisibleScans) > completedAtRequest
            if ((!force || alreadyCompleted) && !cacheInvalidated && GalleryCatalogStore.hasFreshCatalog(
                    context.applicationContext, includeHidden, allFilesAccess, 30_000L)) {
                val stored = GalleryCatalogStore.readMedia(context.applicationContext, includeHidden)
                cacheResult(stored, includeHidden, allFilesAccess)
                stored
            } else scanCurrentMedia(context, includeHidden, allFilesAccess)
        }
    }

    private tailrec fun scanCurrentMedia(context: Context, includeHidden: Boolean, allFilesAccess: Boolean): List<MediaItem> {
        val revision = GalleryCatalogStore.currentMutationRevision()
        val changeToken = GalleryCatalogStore.mediaStoreChangeToken(context)
        val items = ArrayList<MediaItem>()
        loadFromFilesCollection(context, items)
        if (includeHidden) {
            loadFromHiddenFilesystem(context, items, allFilesAccess)
        }
        items.sortByDescending { it.dateAdded }
        // Remember generated-cache parents from the complete scan before any
        // screen, search, selection or media-type filter takes a smaller subset.
        AutomaticHiddenAlbums.keysForMedia(context, items,
            HiddenDirectoryMarkers(Environment.getExternalStorageDirectory()))
        if (changeToken != GalleryCatalogStore.mediaStoreChangeToken(context) ||
            !GalleryCatalogStore.writeMediaIfCurrent(context.applicationContext, items, includeHidden, allFilesAccess, revision, changeToken)) {
            // A targeted album refresh/mutation finished during the scan. Never
            // overwrite its newer rows or certify the obsolete global snapshot.
            return scanCurrentMedia(context, includeHidden, allFilesAccess)
        }
        cacheResult(items, includeHidden, allFilesAccess)
        VideoThumbnailFrames.scheduleOrphanCleanup(context)
        if (includeHidden) completedHiddenScans++ else completedVisibleScans++
        return items
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
        // A physical album must not wait for a device-wide dirty catalog or scan.
        if (isPhysicalAlbum(albumKey)) return queryAlbumMedia(context, albumKey!!, includeHiddenFilesystem)
        val prefs = context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
        val hiddenKeys = prefs.getStringSet("hidden_folder_keys", emptySet()).orEmpty()
        if (albumKey == VirtualAlbumRules.TRASH_KEY) {
            val trash = loadTrashedMedia(context)
            val visibleAndTrash = loadMedia(context, false) + trash
            return VirtualAlbumRules.visibleTrash(
                trash,
                hiddenKeys + AutomaticHiddenAlbums.keysForMedia(context, visibleAndTrash,
                    HiddenDirectoryMarkers(Environment.getExternalStorageDirectory())),
                prefs.getBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, false)
            )
        }
        val allFilesAccess = MediaActions.hasAllFilesAccess(context)
        val includeHidden = StorageAccessRules.includeHiddenFilesystem(includeHiddenFilesystem, allFilesAccess)
        if (GalleryCatalogStore.isCatalogDirty(context, includeHidden)) {
            val items = refreshMedia(context, includeHidden, force = true)
            val markerKeys = AutomaticHiddenAlbums.keysForMedia(context, items,
                HiddenDirectoryMarkers(Environment.getExternalStorageDirectory()))
            return VirtualAlbumRules.mediaForAlbum(items, albumKey, favoriteUris(context), hiddenKeys + markerKeys)
        }
        if (albumKey == "all_media" || albumKey == VirtualAlbumRules.RECENT_KEY || albumKey == VirtualAlbumRules.FAVORITES_KEY) {
            val items = loadMedia(context, includeHidden)
            val markerKeys = AutomaticHiddenAlbums.keysForMedia(context, items,
                HiddenDirectoryMarkers(Environment.getExternalStorageDirectory()))
            return VirtualAlbumRules.mediaForAlbum(items, albumKey, favoriteUris(context), hiddenKeys + markerKeys)
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
                loadFromHiddenFilesystem(context, hiddenItems, allFilesAccess)
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
        buildAlbums(queryOverviewMedia(context, includeHiddenFilesystem, emptySet(), includeHiddenFilesystem))
            .sortedByDescending { it.latestDate }

    internal fun isPhysicalAlbum(key: String?): Boolean =
        !key.isNullOrEmpty() && key != "all_media" && key != "root" && !VirtualAlbumRules.isVirtual(key)

    internal fun queryIndexedMedia(context: Context): List<MediaItem> =
        ArrayList<MediaItem>().also { loadFromFilesCollection(context, it) }

    /** UI reconciliation: indexed media plus only the hidden directories requested
     * by the screen. Discovery of unknown hidden directories remains explicit. */
    internal fun queryOverviewMedia(context: Context, includeHidden: Boolean,
        temporarilyVisible: Set<String>, showNaturallyHidden: Boolean,
        indexedMedia: List<MediaItem>? = null): List<MediaItem> {
        val indexed = indexedMedia ?: queryIndexedMedia(context)
        if (!StorageAccessRules.includeHiddenFilesystem(includeHidden, MediaActions.hasAllFilesAccess(context))) return indexed
        val requested = temporarilyVisible.toMutableSet()
        if (showNaturallyHidden) {
            requested.addAll(GalleryCatalogStore.readAlbums(context, true).map { it.key })
        }
        val scoped = requested.filter(::isPhysicalAlbum).associateWith { queryAlbumMedia(context, it, true) }
        return indexed.filterNot { it.albumKey in scoped } + scoped.values.flatten()
    }

    /** Query just this directory, even when a full reconciliation is pending. */
    internal fun queryAlbumMedia(context: Context, key: String, includeHiddenFilesystem: Boolean): List<MediaItem> {
        val items = ArrayList<MediaItem>()
        loadFromFilesCollection(context, items, key)
        if (StorageAccessRules.includeHiddenFilesystem(includeHiddenFilesystem, MediaActions.hasAllFilesAccess(context))) {
            val root = Environment.getExternalStorageDirectory()
            val parts = key.replace('\\', '/').split('/').filter { it.isNotEmpty() }
            if (!key.startsWith('/') && parts.none { it == "." || it == ".." }) {
                val directory = File(root, key)
                // Canonical containment also rejects links pointing outside storage.
                if (runCatching { directory.canonicalPath.startsWith(root.canonicalPath + File.separator) }.getOrDefault(false)) {
                    val known = items.mapTo(HashSet()) { dedupeKey(it.relativePath, it.name, it.size) }
                    val durations = HiddenVideoDurationCache(context)
                    directory.listFiles()?.forEach { file ->
                        if (file.isFile && file.length() > 0 && isSupportedMediaFile(file)) {
                            appendFilesystemMedia(root, file, items, known, durations)
                        }
                    }
                }
            }
        }
        items.sortByDescending { it.dateAdded }
        return items
    }

    internal fun refreshAlbumMedia(context: Context, key: String, includeHidden: Boolean) {
        val items = queryAlbumMedia(context, key, includeHidden)
        GalleryCatalogStore.writeAlbumMedia(context, items, includeHidden, key)
    }

    internal fun currentIndexedAlbumCounts(context: Context): Map<String, Int>? {
        if (MediaActions.mediaLibraryAccess(context) != MediaActions.MediaLibraryAccess.FULL) return null
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.BUCKET_ID)
        } else arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.BUCKET_ID)
        return runCatching {
            val counts = HashMap<String, Int>()
            context.contentResolver.query(MediaStore.Files.getContentUri("external"), projection,
                "(${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?,?) OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE ? OR ${MediaStore.MediaColumns.MIME_TYPE} LIKE ?) AND ${MediaStore.MediaColumns.SIZE} > 0",
                arrayOf("1", "3", "image/%", "video/%"), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getString(0)
                        else pathFromData(cursor.getString(0))
                    val key = path?.takeIf { it.isNotEmpty() } ?: cursor.getString(1).orEmpty()
                    counts[key] = (counts[key] ?: 0) + 1
                }
            } ?: return null
            counts
        }.getOrNull()
    }

    @JvmStatic
    fun loadTrashedMedia(context: Context): List<MediaItem> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return LegacyTrashStore.load(context)
        val items = ArrayList<MediaItem>()
        loadFromFilesCollection(context, items, trashedOnly = true)
        items.addAll(LegacyTrashStore.load(context))
        return items
    }

    private fun loadFromFilesCollection(
        context: Context,
        output: MutableList<MediaItem>,
        albumKey: String? = null,
        trashedOnly: Boolean = false
    ): Boolean {
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
            if (cursor == null) return false
            cursor.use { cursor ->
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
            return true
        } catch (_: SecurityException) {
            // Recent Android versions may grant only photos or only videos.
            return false
        }
    }

    private fun favoriteUris(context: Context): Set<String> =
        context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
            .getStringSet("favorites", emptySet()).orEmpty()

    private fun loadFromHiddenFilesystem(context: Context, output: MutableList<MediaItem>, allFilesAccess: Boolean) {
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
        scanDirectory(root, root, output, known, 0, false, HiddenVideoDurationCache(context))
    }

    private fun scanDirectory(
        root: File,
        dir: File?,
        output: MutableList<MediaItem>,
        known: MutableSet<String>,
        depth: Int,
        insideHiddenArea: Boolean,
        durations: HiddenVideoDurationCache
    ) {
        if (dir == null || depth > 24 || shouldSkipDirectory(root, dir)) {
            return
        }
        val hiddenArea = insideHiddenArea || isHiddenMediaDirectory(dir)
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.isDirectory) {
                scanDirectory(root, file, output, known, depth + 1, hiddenArea, durations)
            } else if (hiddenArea && file.isFile && file.length() > 0 && isSupportedMediaFile(file)) {
                appendFilesystemMedia(root, file, output, known, durations)
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

    internal fun isSupportedMediaFile(file: File): Boolean {
        val name = file.name.lowercase(Locale.US)
        // MediaProvider retains trashed/pending files under these names, including
        // in .nomedia folders. A filesystem scan must not resurrect them as media.
        if (name.startsWith(".trashed-") || name.startsWith(".pending-")) return false
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

    private fun appendFilesystemMedia(root: File, file: File, output: MutableList<MediaItem>,
        known: MutableSet<String>, durations: HiddenVideoDurationCache) {
        val path = relativeFolder(root, file)
        if (!known.add(dedupeKey(path, file.name, file.length()))) return
        val mime = mimeFor(file)
        output.add(MediaItem(-file.absolutePath.hashCode().toLong().absoluteValue, Uri.fromFile(file),
            file.name, mime, maxOf(1L, file.lastModified() / 1000L), file.length(), path,
            if (path.isEmpty()) file.parent.orEmpty() else path,
            cleanAlbumName(path, file.parentFile?.name ?: "Galeria"),
            if (mime.startsWith("video/")) durations.duration(file, ::durationForFile) else 0L))
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
