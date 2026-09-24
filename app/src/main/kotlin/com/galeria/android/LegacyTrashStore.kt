package com.galeria.android

import android.app.Activity
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.util.Base64
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.absoluteValue

object LegacyTrashStore {
    private const val PREF_ENTRIES = "legacy_trash_entries"
    private const val DIRECTORY = ".Galeria-Lixeira"

    fun trash(activity: Activity, uri: Uri): Boolean {
        val source = MediaActions.fileFromMediaStore(activity, uri) ?: return false
        if (!source.exists()) return false
        val trashDir = File(Environment.getExternalStorageDirectory(), DIRECTORY)
        if (!trashDir.exists() && !trashDir.mkdirs()) return false
        val target = uniqueFile(trashDir, source.name)
        if (!move(source, target)) return false
        val mime = mimeFor(target)
        val entries = entries(activity).toMutableSet()
        entries.add(encode(Entry(target.absolutePath, source.absolutePath, mime, System.currentTimeMillis())))
        save(activity, entries)
        scan(activity, source.absolutePath, target.absolutePath)
        return true
    }

    fun restore(activity: Activity, uri: Uri): Boolean {
        val source = file(uri) ?: return false
        val encoded = entries(activity).firstOrNull { decode(it)?.trashPath == source.absolutePath } ?: return false
        val entry = decode(encoded) ?: return false
        val requestedTarget = File(entry.originalPath)
        requestedTarget.parentFile?.mkdirs()
        val target = if (requestedTarget.exists()) uniqueFile(requestedTarget.parentFile, requestedTarget.name) else requestedTarget
        if (!move(source, target)) return false
        val updated = entries(activity).toMutableSet().apply { remove(encoded) }
        save(activity, updated)
        scan(activity, source.absolutePath, target.absolutePath)
        return true
    }

    fun forget(context: Context, uri: Uri) {
        val path = file(uri)?.absolutePath ?: return
        val updated = entries(context).filterNot { decode(it)?.trashPath == path }.toSet()
        save(context, updated)
    }

    fun load(context: Context): List<MediaItem> {
        val validEncoded = LinkedHashSet<String>()
        val result = ArrayList<MediaItem>()
        for (encoded in entries(context)) {
            val entry = decode(encoded) ?: continue
            val file = File(entry.trashPath)
            if (!file.exists() || file.length() <= 0L) continue
            val originalFolder = File(entry.originalPath).parentFile
            val relativeFolder = originalFolder?.relativeToOrNull(Environment.getExternalStorageDirectory())
                ?.invariantSeparatorsPath?.trimEnd('/')?.let { if (it.isEmpty()) "" else "$it/" }
                ?: originalFolder?.absolutePath.orEmpty()
            validEncoded.add(encoded)
            result.add(
                MediaItem(
                    -file.absolutePath.hashCode().toLong().absoluteValue,
                    Uri.fromFile(file),
                    file.name,
                    entry.mimeType,
                    entry.trashedAt / 1000L,
                    file.length(),
                    relativeFolder,
                    relativeFolder,
                    originalFolder?.name ?: "Lixeira"
                )
            )
        }
        if (validEncoded.size != entries(context).size) save(context, validEncoded)
        return result.sortedByDescending { it.dateAdded }
    }

    fun isTrashDirectory(dir: File): Boolean = dir.name == DIRECTORY

    private fun entries(context: Context): Set<String> =
        context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
            .getStringSet(PREF_ENTRIES, emptySet()).orEmpty()

    private fun save(context: Context, values: Set<String>) {
        context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(PREF_ENTRIES, HashSet(values)).apply()
    }

    private fun move(source: File, target: File): Boolean {
        if (source.renameTo(target)) return true
        return try {
            FileInputStream(source).use { input -> FileOutputStream(target).use { output -> input.copyTo(output) } }
            if (!source.delete()) {
                target.delete()
                false
            } else true
        } catch (_: Exception) {
            target.delete()
            false
        }
    }

    private fun uniqueFile(parent: File?, name: String): File {
        val directory = parent ?: return File(name)
        var candidate = File(directory, name)
        if (!candidate.exists()) return candidate
        val base = name.substringBeforeLast('.', name)
        val extension = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var suffix = 1
        while (candidate.exists()) candidate = File(directory, "$base ($suffix)$extension").also { suffix++ }
        return candidate
    }

    private fun mimeFor(file: File): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase())
        ?: if (file.extension.lowercase() in setOf("mp4", "mkv", "webm", "avi", "mov")) "video/*" else "image/*"

    private fun scan(context: Context, vararg paths: String) {
        MediaScannerConnection.scanFile(context, paths, null, null)
        MediaStoreRepository.invalidateCache()
        GalleryCatalogStore.markCatalogDirty(context.applicationContext)
    }

    private fun file(uri: Uri): File? = uri.path?.takeIf { uri.scheme == "file" }?.let(::File)

    private fun encode(entry: Entry): String = listOf(
        entry.trashPath,
        entry.originalPath,
        entry.mimeType,
        entry.trashedAt.toString()
    ).joinToString("|") { Base64.encodeToString(it.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE) }

    private fun decode(value: String): Entry? = runCatching {
        val parts = value.split('|').map {
            String(Base64.decode(it, Base64.NO_WRAP or Base64.URL_SAFE), Charsets.UTF_8)
        }
        Entry(parts[0], parts[1], parts[2], parts[3].toLong())
    }.getOrNull()

    private data class Entry(
        val trashPath: String,
        val originalPath: String,
        val mimeType: String,
        val trashedAt: Long
    )
}
