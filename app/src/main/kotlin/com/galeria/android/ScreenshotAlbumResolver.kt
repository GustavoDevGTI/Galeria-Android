package com.galeria.android

import android.content.Context
import android.os.Build
import android.provider.MediaStore

/** Worker-only, scoped metadata lookup. No image decoding or filesystem scan.
 * Imported legacy screenshots must not win just because DATE_ADDED is newer. */
internal object ScreenshotAlbumResolver {
    fun activeKey(context: Context, albums: List<AlbumItem>): String? {
        val candidates = albums.filter(VirtualAlbumRules::isScreenshot)
        if (candidates.size < 2 || Build.VERSION.SDK_INT < 29) return VirtualAlbumRules.newestScreenshotKey(candidates)
        val paths = candidates.map { it.path }.filter { it.isNotBlank() }.distinct()
        if (paths.isEmpty()) return VirtualAlbumRules.newestScreenshotKey(candidates)
        val capturedAt = runCatching {
            val datesByPath = HashMap<String, Long>()
            val columns = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME)
            context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, columns,
                "${MediaStore.MediaColumns.RELATIVE_PATH} IN (${paths.joinToString(",") { "?" }}) AND ${MediaStore.MediaColumns.SIZE} > 0",
                paths.toTypedArray(), null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val path = cursor.getString(0)
                    val captured = VirtualAlbumRules.captureTime(cursor.getLong(1), cursor.getLong(2), cursor.getLong(3), cursor.getString(4).orEmpty())
                    datesByPath[path] = maxOf(datesByPath[path] ?: 0L, captured)
                }
            }
            candidates.mapNotNull { album -> datesByPath[album.path]?.let { album.key to it } }.toMap()
        }.onFailure { android.util.Log.w("AlbumOrdering", "Não foi possível ler datas das capturas", it) }.getOrDefault(emptyMap())
        return VirtualAlbumRules.newestScreenshotKey(candidates, capturedAt)
    }
}
