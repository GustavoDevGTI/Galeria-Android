package com.galeria.android

import android.content.Context
import androidx.core.content.edit
import java.io.File

/** Persist metadata, not media. Never opens an unchanged video a second time. */
internal class HiddenVideoDurationCache(context: Context) {
    private val prefs = context.getSharedPreferences("hidden_video_durations", Context.MODE_PRIVATE)
    private var entryCount = prefs.all.size

    fun duration(file: File, extract: (File) -> Long): Long {
        val stamp = "${file.length()}:${file.lastModified()}:"
        val previous = prefs.getString(file.absolutePath, null)
        if (previous?.startsWith(stamp) == true) {
            previous.removePrefix(stamp).toLongOrNull()?.takeIf { it >= 0L }?.let { return it }
        }
        val duration = extract(file).coerceAtLeast(0L)
        // Bound storage: clearing this metadata does not delete files or thumbnails.
        if (!prefs.contains(file.absolutePath)) {
            if (entryCount >= 4096) {
                prefs.edit { clear() }
                entryCount = 0
            }
            entryCount++
        }
        prefs.edit { putString(file.absolutePath, "$stamp$duration") }
        return duration
    }
}
