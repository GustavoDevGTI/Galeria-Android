package com.galeria.android

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import java.io.File

/**
 * "Manter data de modificação": moving keeps the file's original date; with it off, a moved or
 * copied file takes the current time. Renames already keep the date, so only copies need help.
 */
internal object ModifiedDatePolicy {
    fun dateAfterTransfer(keep: Boolean, sourceModifiedMs: Long, nowMs: Long): Long =
        if (keep && sourceModifiedMs > 0L) sourceModifiedMs else nowMs

    fun apply(context: Context, target: File?, sourceModifiedMs: Long) {
        if (target == null || !target.exists()) return
        val keep = GalleryPreferences.flag(context, GalleryPreferences.KEEP_MODIFIED_DATE)
        val wanted = dateAfterTransfer(keep, sourceModifiedMs, System.currentTimeMillis())
        if (target.lastModified() != wanted && target.setLastModified(wanted)) {
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
        }
    }
}

/**
 * "Apagar pastas vazias": after media leaves a folder, the folder goes away when nothing is left.
 * Files in the Android trash stay inside the folder (hidden), so a folder with trashed media is not
 * empty and is kept. Standard system folders are never removed.
 */
internal object EmptyFolderRules {
    private val protectedFolders = setOf(
        "dcim", "dcim/camera", "pictures", "pictures/screenshots", "dcim/screenshots", "movies",
        "download", "documents", "music", "podcasts", "ringtones", "alarms", "notifications", "audiobooks",
        "recordings"
    )

    fun mayRemove(relativePath: String?): Boolean {
        val path = relativePath?.replace('\\', '/')?.trim('/')?.lowercase() ?: return false
        if (path.isBlank() || path.split('/').any { it == "." || it == ".." }) return false
        return path !in protectedFolders && path != "android" && !path.startsWith("android/")
    }

    /** Only a folder holding nothing but a ".nomedia" marker counts as empty. */
    fun isEmpty(entryNames: List<String>): Boolean = entryNames.all { it == ".nomedia" }
}

internal object EmptyFolderCleaner {
    fun afterRemoval(context: Context, relativePaths: Collection<String?>) {
        val app = context.applicationContext
        if (!GalleryPreferences.flag(app, GalleryPreferences.DELETE_EMPTY_FOLDERS)) return
        if (!MediaActions.hasAllFilesAccess(app)) return
        val root = Environment.getExternalStorageDirectory().canonicalFile
        var removed = false
        relativePaths.filterNotNull().filter(EmptyFolderRules::mayRemove).distinct().forEach { relative ->
            val folder = File(root, relative.trim('/')).canonicalFile
            if (!folder.path.startsWith(root.path + File.separator) || !folder.isDirectory) return@forEach
            val entries = folder.listFiles() ?: return@forEach
            if (!EmptyFolderRules.isEmpty(entries.map { it.name })) return@forEach
            entries.forEach { it.delete() }
            if (folder.delete()) removed = true
        }
        if (removed) {
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(app)
        }
    }
}
