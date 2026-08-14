package com.galeria.android

import android.app.Activity
import android.app.PendingIntent
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections

class MediaActions private constructor() {
    companion object {
        const val RESULT_DONE = 1
        const val RESULT_NEEDS_PERMISSION = 2
        const val RESULT_FAILED = 3

        @JvmStatic
        fun hasAllFilesAccess(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager())

        @JvmStatic
        fun requestAllFilesAccess(activity: Activity) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()) {
                return
            }
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            } catch (_: Exception) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        }

        @JvmStatic
        fun requestDelete(activity: Activity, uri: Uri, requestCode: Int): Int {
            if (hasAllFilesAccess(activity)) {
                return if (deleteDirect(activity, uri)) RESULT_DONE else RESULT_FAILED
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return try {
                    val moveToTrash = activity.getSharedPreferences(Ui.PREFS, Activity.MODE_PRIVATE)
                        .getBoolean("move_to_trash", false)
                    val pendingIntent = if (moveToTrash) {
                        MediaStore.createTrashRequest(activity.contentResolver, Collections.singletonList(uri), true)
                    } else {
                        MediaStore.createDeleteRequest(activity.contentResolver, Collections.singletonList(uri))
                    }
                    activity.startIntentSenderForResult(
                        pendingIntent.intentSender,
                        requestCode,
                        null,
                        0,
                        0,
                        0
                    )
                    RESULT_NEEDS_PERMISSION
                } catch (_: Exception) {
                    val deleted = deleteDirect(activity, uri)
                    if (!deleted) {
                        Ui.toast(activity, "Não foi possível pedir permissão para excluir.")
                    }
                    if (deleted) RESULT_DONE else RESULT_FAILED
                }
            }

            val deleted = deleteDirect(activity, uri)
            Ui.toast(activity, if (deleted) "Item excluído." else "Não foi possível excluir.")
            return if (deleted) RESULT_DONE else RESULT_FAILED
        }

        @JvmStatic
        fun requestPermanentDelete(activity: Activity, uri: Uri, requestCode: Int): Int {
            if (hasAllFilesAccess(activity)) {
                return if (deleteDirect(activity, uri)) RESULT_DONE else RESULT_FAILED
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return try {
                    val pendingIntent = MediaStore.createDeleteRequest(
                        activity.contentResolver,
                        Collections.singletonList(uri)
                    )
                    activity.startIntentSenderForResult(
                        pendingIntent.intentSender,
                        requestCode,
                        null,
                        0,
                        0,
                        0
                    )
                    RESULT_NEEDS_PERMISSION
                } catch (_: Exception) {
                    val deleted = deleteDirect(activity, uri)
                    if (!deleted) {
                        Ui.toast(activity, "Não foi possível pedir permissão para excluir.")
                    }
                    if (deleted) RESULT_DONE else RESULT_FAILED
                }
            }
            return if (deleteDirect(activity, uri)) RESULT_DONE else RESULT_FAILED
        }

        @JvmStatic
        fun deleteDirect(activity: Activity, uri: Uri): Boolean {
            val file = fileFromMediaStore(activity, uri)
            if (hasAllFilesAccess(activity) && file != null && file.exists()) {
                val path = file.absolutePath
                val deleted = file.delete()
                if (deleted) {
                    try {
                        activity.contentResolver.delete(uri, null, null)
                    } catch (_: Exception) {
                    }
                    MediaScannerConnection.scanFile(activity, arrayOf(path), null, null)
                    MediaStoreRepository.invalidateCache()
                    return true
                }
            }
            return try {
                val deleted = activity.contentResolver.delete(uri, null, null) > 0
                if (deleted) {
                    MediaStoreRepository.invalidateCache()
                }
                deleted
            } catch (_: Exception) {
                false
            }
        }

        @JvmStatic
        fun mediaExists(activity: Activity, uri: Uri): Boolean {
            return try {
                activity.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns._ID),
                    null,
                    null,
                    null
                )?.use { cursor -> cursor.moveToFirst() } ?: false
            } catch (_: Exception) {
                false
            }
        }

        @JvmStatic
        fun requestWrite(activity: Activity, uri: Uri, requestCode: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val pendingIntent = MediaStore.createWriteRequest(
                        activity.contentResolver,
                        Collections.singletonList(uri)
                    )
                    activity.startIntentSenderForResult(
                        pendingIntent.intentSender,
                        requestCode,
                        null,
                        0,
                        0,
                        0
                    )
                } catch (_: Exception) {
                    Ui.toast(activity, "Não foi possível pedir permissão para mover.")
                }
            }
        }

        @JvmStatic
        fun moveToFolder(activity: Activity, item: MediaItem, folderName: String): Int {
            val relativePath = destinationRelativePath(folderName, item.isVideo())
            if (relativePath.isEmpty()) {
                return RESULT_FAILED
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                try {
                    val updated = activity.contentResolver.update(item.uri, values, null, null)
                    if (updated > 0) {
                        MediaStoreRepository.invalidateCache()
                        GalleryCatalogStore.markCatalogDirty(activity.applicationContext)
                        return RESULT_DONE
                    }
                } catch (_: SecurityException) {
                    if (!hasAllFilesAccess(activity)) return RESULT_NEEDS_PERMISSION
                } catch (_: Exception) {
                    // File-backed hidden media is moved by the direct fallback below.
                }
                return if (hasAllFilesAccess(activity)) {
                    moveToFolderDirect(activity, item, relativePath)
                } else {
                    RESULT_NEEDS_PERMISSION
                }
            }

            return moveToFolderDirect(activity, item, relativePath)
        }

        @JvmStatic
        fun copyToFolder(activity: Activity, item: MediaItem, folderName: String): Int {
            val relativePath = destinationRelativePath(folderName, item.isVideo())
            if (relativePath.isEmpty()) {
                return RESULT_FAILED
            }

            val isVideo = item.isVideo()
            if (hasAllFilesAccess(activity)) {
                return copyToFolderDirect(activity, item, relativePath)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = if (isVideo) {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, item.mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = activity.contentResolver
                val targetUri = resolver.insert(collection, values) ?: return RESULT_FAILED
                val copied = copyUri(activity, item.uri, targetUri)
                if (!copied) {
                    resolver.delete(targetUri, null, null)
                    return RESULT_FAILED
                }
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(targetUri, done, null, null)
                MediaStoreRepository.invalidateCache()
                GalleryCatalogStore.markCatalogDirty(activity.applicationContext)
                return RESULT_DONE
            }

            val currentFile = fileFromMediaStore(activity, item.uri)
            if (currentFile == null || !currentFile.exists()) {
                return RESULT_FAILED
            }
            val targetDir = File(Environment.getExternalStorageDirectory(), relativePath)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return RESULT_FAILED
            }
            val targetFile = uniqueFile(targetDir, currentFile.name)
            return try {
                activity.contentResolver.openInputStream(item.uri).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        if (input == null) return RESULT_FAILED
                        copy(input, output)
                    }
                }
                MediaScannerConnection.scanFile(activity, arrayOf(targetFile.absolutePath), null, null)
                MediaStoreRepository.invalidateCache()
                GalleryCatalogStore.markCatalogDirty(activity.applicationContext)
                RESULT_DONE
            } catch (_: Exception) {
                targetFile.delete()
                RESULT_FAILED
            }
        }

        @JvmStatic
        fun copyToHidden(activity: Activity, item: MediaItem): File? {
            val hiddenDir = hiddenDir(activity)
            if (!hiddenDir.exists() && !hiddenDir.mkdirs()) {
                return null
            }

            val noMedia = File(hiddenDir, ".nomedia")
            try {
                if (!noMedia.exists()) noMedia.createNewFile()
            } catch (_: Exception) {
            }

            val target = uniqueFile(hiddenDir, item.name)
            return try {
                activity.contentResolver.openInputStream(item.uri).use { input ->
                    FileOutputStream(target).use { output ->
                        if (input == null) return null
                        copy(input, output)
                    }
                }
                target
            } catch (_: Exception) {
                target.delete()
                null
            }
        }

        @JvmStatic
        fun restoreHiddenFile(activity: Activity, file: File, mimeType: String): Boolean {
            if (!file.exists()) {
                return false
            }

            val isVideo = mimeType.startsWith("video/")
            val collection = if (isVideo) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val baseDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$baseDir/Galeria Restaurada/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }

            val resolver = activity.contentResolver
            val targetUri = resolver.insert(collection, values) ?: return false

            try {
                FileInputStream(file).use { input ->
                    resolver.openOutputStream(targetUri).use { output ->
                        if (output == null) return false
                        copy(input, output)
                    }
                }
            } catch (_: Exception) {
                resolver.delete(targetUri, null, null)
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(targetUri, done, null, null)
            }

            val restored = file.delete()
            if (restored) {
                MediaStoreRepository.invalidateCache()
            }
            return restored
        }

        @JvmStatic
        fun hiddenDir(activity: Activity): File = File(activity.getExternalFilesDir(null), "Hidden")

        @JvmStatic
        fun createFolder(activity: Activity, target: File): Boolean {
            if (target.exists()) {
                return target.isDirectory
            }
            val created = target.mkdirs()
            if (created) {
                MediaStoreRepository.invalidateCache()
            }
            return created
        }

        @JvmStatic
        fun cleanFolderName(value: String?): String =
            value
                ?.trim()
                ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                ?.replace(Regex("\\s+"), " ")
                .orEmpty()

        @JvmStatic
        fun destinationRelativePath(value: String?, isVideo: Boolean): String {
            var normalized = value
                ?.trim()
                ?.replace('\\', '/')
                ?.trim('/')
                .orEmpty()
            if (normalized.isEmpty()) return ""

            val storageRoot = Environment.getExternalStorageDirectory().absolutePath
                .replace('\\', '/')
                .trimEnd('/')
            if (normalized.startsWith(storageRoot, ignoreCase = true)) {
                normalized = normalized.substring(storageRoot.length).trim('/')
            }
            if (!normalized.contains('/')) {
                val baseDir = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                normalized = "$baseDir/${cleanFolderName(normalized)}"
            } else {
                normalized = normalized.split('/')
                    .filter { it.isNotBlank() }
                    .joinToString("/") { cleanFolderName(it) }
            }
            return if (normalized.isEmpty()) "" else "$normalized/"
        }

        private fun copyUri(activity: Activity, sourceUri: Uri, targetUri: Uri): Boolean {
            return try {
                activity.contentResolver.openInputStream(sourceUri).use { input ->
                    activity.contentResolver.openOutputStream(targetUri).use { output ->
                        if (input == null || output == null) return false
                        copy(input, output)
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        private fun fileFromMediaStore(activity: Activity, uri: Uri): File? {
            if (uri.scheme == "file") {
                return File(uri.path.orEmpty())
            }
            val projection = arrayOf(MediaStore.MediaColumns.DATA)
            return try {
                activity.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val index = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                        File(cursor.getString(index))
                    } else {
                        null
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun moveToFolderDirect(activity: Activity, item: MediaItem, relativePath: String): Int {
            val currentFile = fileFromMediaStore(activity, item.uri)
            if (currentFile == null || !currentFile.exists()) {
                return RESULT_FAILED
            }

            val targetDir = File(Environment.getExternalStorageDirectory(), relativePath)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return RESULT_FAILED
            }

            val targetFile = uniqueFile(targetDir, currentFile.name)
            var moved = currentFile.renameTo(targetFile)
            if (!moved) {
                try {
                    activity.contentResolver.openInputStream(item.uri).use { input ->
                        FileOutputStream(targetFile).use { output ->
                            if (input == null) return RESULT_FAILED
                            copy(input, output)
                        }
                    }
                    moved = currentFile.delete()
                } catch (_: Exception) {
                    targetFile.delete()
                    return RESULT_FAILED
                }
            }

            if (moved) {
                try {
                    activity.contentResolver.delete(item.uri, null, null)
                } catch (_: Exception) {
                }
                MediaScannerConnection.scanFile(
                    activity,
                    arrayOf(targetFile.absolutePath, currentFile.absolutePath),
                    null,
                    null
                )
                MediaStoreRepository.invalidateCache()
                GalleryCatalogStore.markCatalogDirty(activity.applicationContext)
                return RESULT_DONE
            }
            targetFile.delete()
            return RESULT_FAILED
        }

        private fun copyToFolderDirect(activity: Activity, item: MediaItem, relativePath: String): Int {
            val currentFile = fileFromMediaStore(activity, item.uri)
            if (currentFile == null || !currentFile.exists()) {
                return RESULT_FAILED
            }
            val targetDir = File(Environment.getExternalStorageDirectory(), relativePath)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                return RESULT_FAILED
            }
            val targetFile = uniqueFile(targetDir, currentFile.name)
            return try {
                activity.contentResolver.openInputStream(item.uri).use { input ->
                    FileOutputStream(targetFile).use { output ->
                        if (input == null) return RESULT_FAILED
                        copy(input, output)
                    }
                }
                MediaScannerConnection.scanFile(activity, arrayOf(targetFile.absolutePath), null, null)
                MediaStoreRepository.invalidateCache()
                GalleryCatalogStore.markCatalogDirty(activity.applicationContext)
                RESULT_DONE
            } catch (_: Exception) {
                targetFile.delete()
                RESULT_FAILED
            }
        }

        private fun uniqueFile(dir: File, originalName: String?): File {
            val safeName = if (originalName.isNullOrBlank()) "media" else originalName.trim()
            var candidate = File(dir, safeName)
            if (!candidate.exists()) {
                return candidate
            }

            var name = safeName
            var ext = ""
            val dot = safeName.lastIndexOf('.')
            if (dot > 0) {
                name = safeName.substring(0, dot)
                ext = safeName.substring(dot)
            }

            var count = 1
            do {
                candidate = File(dir, "$name-$count$ext")
                count++
            } while (candidate.exists())
            return candidate
        }

        private fun copy(input: InputStream, output: OutputStream) {
            val buffer = ByteArray(8192)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                output.write(buffer, 0, read)
            }
        }
    }
}
