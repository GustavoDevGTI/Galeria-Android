package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/** Só troca o quadro inicial quando ele é praticamente preto, sem variar aleatoriamente a cada abertura. */
object VideoThumbnailFrames {
    private const val MAX_CACHED = 512
    private const val MAX_PENDING = 48
    private val worker = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val selectedMillis = object : LinkedHashMap<String, Long>(MAX_CACHED, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > MAX_CACHED
    }
    private val waiting = HashMap<String, MutableList<(Long) -> Unit>>()

    fun selectedTime(context: Context, item: MediaItem, onReady: (Long) -> Unit): Long? {
        if (!item.isVideo()) return 0L
        val key = identity(item)
        synchronized(lock) {
            selectedMillis[key]?.let { return it }
            waiting[key]?.let { it.add(onReady); return null }
            if (waiting.size >= MAX_PENDING) return null
            waiting[key] = mutableListOf(onReady)
        }
        worker.execute {
            val time = runCatching { inspect(context.applicationContext, item) }.getOrDefault(0L)
            val callbacks = synchronized(lock) {
                selectedMillis[key] = time
                waiting.remove(key).orEmpty()
            }
            mainHandler.post { callbacks.forEach { it(time) } }
        }
        return null
    }

    private fun identity(item: MediaItem): String = "${item.uri}|${item.size}|${item.dateAdded}"

    private fun inspect(context: Context, item: MediaItem): Long {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, item.uri)
            val opening = frame(retriever, 0L) ?: return 0L
            val isBlank = try { VideoThumbnailRules.isBlank(opening) } finally { opening.recycle() }
            if (!isBlank) return 0L
            val duration = item.duration.takeIf { it > 0L }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return 0L
            if (duration < 1_000L) return 0L
            val candidates = VideoThumbnailRules.candidateMillis(duration, item.uri.toString())
            for (candidate in candidates) {
                val bitmap = frame(retriever, candidate * 1_000L) ?: continue
                val blank = try { VideoThumbnailRules.isBlank(bitmap) } finally { bitmap.recycle() }
                if (!blank) return candidate
            }
            return 0L
        } finally {
            retriever.release()
        }
    }

    private fun frame(retriever: MediaMetadataRetriever, micros: Long): Bitmap? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST, 64, 64)
        } else {
            retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST)
        }
}

object VideoThumbnailRules {
    fun candidateMillis(duration: Long, identity: String): List<Long> {
        val seededPercent = 25L + (identity.hashCode().toLong() and 0x7fffffffL) % 51L
        return listOf(seededPercent, 50L, 75L, 25L).distinct()
            .map { duration * it / 100L }
            .filter { it in 1 until duration }
    }

    fun isBlank(bitmap: Bitmap): Boolean {
        val stepX = (bitmap.width / 8).coerceAtLeast(1)
        val stepY = (bitmap.height / 8).coerceAtLeast(1)
        val pixels = ArrayList<Int>()
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) pixels.add(bitmap.getPixel(x, y))
        }
        return isBlankPixels(pixels.toIntArray())
    }

    fun isBlankPixels(pixels: IntArray): Boolean {
        var count = 0
        var brightness = 0
        var visible = 0
        var colorful = 0
        for (pixel in pixels) {
                val red = pixel shr 16 and 255
                val green = pixel shr 8 and 255
                val blue = pixel and 255
                val luma = (red * 21 + green * 72 + blue * 7) / 100
                brightness += luma
                if (luma >= 38) visible++
                if (maxOf(red, green, blue) - minOf(red, green, blue) >= 35) colorful++
                count++
        }
        return count > 0 && brightness / count < 22 && visible * 100 < count * 6 && colorful * 100 < count * 6
    }
}
