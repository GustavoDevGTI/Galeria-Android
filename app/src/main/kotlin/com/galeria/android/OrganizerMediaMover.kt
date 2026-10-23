package com.galeria.android

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/** Returns the actual new identity (including collision renames), needed for undo.
 * Uses application context so an in-flight move can outlive the screen. */
internal object OrganizerMediaMover {
    fun discard(context: Context, item: MediaItem, permanently: Boolean): Boolean {
        if (!permanently) {
            if (Build.VERSION.SDK_INT >= 30 && item.uri.scheme == "content") {
                val changed = runCatching { context.contentResolver.update(item.uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_TRASHED, 1)
                }, null, null) > 0 }.getOrDefault(false)
                if (changed) { changed(context); return true }
            }
            return LegacyTrashStore.trash(context, item.uri).also { if (it) changed(context) }
        }
        if (item.uri.scheme == "content" && runCatching {
            context.contentResolver.delete(item.uri, null, null) > 0
        }.getOrDefault(false)) { changed(context); return true }
        if (!MediaActions.hasAllFilesAccess(context)) return false
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val file = MediaActions.fileFromMediaStore(context, item.uri)?.canonicalFile ?: return false
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile || !file.delete()) return false
        runCatching { context.contentResolver.delete(item.uri, null, null) }
        MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null)
        LegacyTrashStore.forget(context, item.uri); changed(context); return true
    }

    fun move(context: Context, item: MediaItem, destination: String, name: String = item.name): MediaItem? {
        val root = Environment.getExternalStorageDirectory().canonicalFile
        val parts = destination.replace('\\', '/').trim('/').split('/')
        if (parts.isEmpty() || parts.any { it.isBlank() || it == "." || it == ".." } || name.isBlank() ||
            name.contains('/') || name.contains('\\') || name == "." || name == "..") return null
        val relative = parts.joinToString("/", postfix = "/")
        val directory = File(root, relative).canonicalFile
        if (!directory.path.startsWith(root.path + File.separator)) return null
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= 29 && item.uri.scheme == "content") {
            try {
                val updated = resolver.update(item.uri, ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                }, null, null)
                if (updated > 0) {
                    val actualName = runCatching {
                        resolver.query(item.uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    }.getOrNull() ?: name
                    changed(context)
                    return relocated(item, item.uri, actualName, relative)
                }
            } catch (_: Exception) { /* A hidden file may require direct access. */ }
        }
        if (!MediaActions.hasAllFilesAccess(context)) return null
        val source = MediaActions.fileFromMediaStore(context, item.uri)?.canonicalFile ?: return null
        if (!source.isFile || (!directory.isDirectory && !directory.mkdirs())) return null
        var target = File(directory, name)
        if (source == target.canonicalFile) return relocated(item, item.uri, source.name, relative)
        var suffix = 1
        while (target.exists()) {
            val stem = File(name).nameWithoutExtension
            val extension = File(name).extension.let { if (it.isEmpty()) "" else ".$it" }
            target = File(directory, "$stem (${suffix++})$extension")
        }
        // Same storage volume: atomic rename. Never copy/delete a large video on the UI thread.
        if (!source.renameTo(target)) return null
        try {
            if (item.uri.scheme == "content") resolver.delete(item.uri, null, null)
        } catch (_: Exception) { }
        MediaScannerConnection.scanFile(context, arrayOf(source.path, target.path), null, null)
        changed(context)
        return relocated(item, Uri.fromFile(target), target.name, relative)
    }

    private fun changed(context: Context) {
        MediaStoreRepository.invalidateCache()
        GalleryCatalogStore.markCatalogDirty(context)
    }

    private fun relocated(item: MediaItem, uri: Uri, name: String, path: String) = MediaItem(
        item.id, uri, name, item.mimeType, item.dateAdded, item.size, path, path,
        path.trimEnd('/').substringAfterLast('/'), item.duration)
}
