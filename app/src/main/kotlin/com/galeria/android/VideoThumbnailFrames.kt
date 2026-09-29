package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors

/** Decide uma vez por arquivo e persiste a imagem final fora do MediaStore. */
object VideoThumbnailFrames {
    private const val MAX_PENDING = 48
    private const val THUMBNAIL_SIDE = 960
    private const val JPEG_QUALITY = 92
    private val worker = Executors.newFixedThreadPool(2)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val waiting = HashMap<String, MutableList<(Uri) -> Unit>>()

    fun cachedThumbnail(context: Context, item: MediaItem): Uri? {
        if (!item.isVideo()) return null
        val target = thumbnailFile(context.applicationContext, item)
        return if (target.length() > 0L) Uri.fromFile(target) else null
    }

    fun thumbnail(context: Context, item: MediaItem, onReady: (Uri) -> Unit): Uri? {
        if (!item.isVideo()) return null
        val key = identity(item)
        val target = thumbnailFile(context.applicationContext, item)
        if (target.length() > 0L) return Uri.fromFile(target)
        synchronized(lock) {
            waiting[key]?.let { it.add(onReady); return null }
            if (waiting.size >= MAX_PENDING) return null
            waiting[key] = mutableListOf(onReady)
        }
        worker.execute {
            val ready = runCatching {
                if (target.length() == 0L) generate(context.applicationContext, item, target)
                if (target.length() > 0L) Uri.fromFile(target) else null
            }.getOrNull()
            val callbacks = synchronized(lock) {
                waiting.remove(key).orEmpty()
            }
            if (ready != null) mainHandler.post { callbacks.forEach { it(ready) } }
        }
        return null
    }

    internal fun thumbnailFile(context: Context, item: MediaItem): File {
        val source = digest(item.uri.toString()).take(20)
        val version = digest(identity(item)).take(20)
        return File(File(File(context.noBackupFilesDir, "video_thumbnails"), source), "$version.jpg")
    }

    private fun identity(item: MediaItem): String = "v2|${item.uri}|${item.size}|${item.dateAdded}"

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun generate(context: Context, item: MediaItem, target: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, item.uri)
            val opening = frame(retriever, 0L, THUMBNAIL_SIDE) ?: return
            var chosen = opening
            try {
                if (VideoThumbnailRules.isBlank(opening)) {
                    val duration = item.duration.takeIf { it > 0L }
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        ?: 0L
                    if (duration >= 1_000L) {
                        for (candidate in VideoThumbnailRules.candidateMillis(duration, item.uri.toString())) {
                            val preview = frame(retriever, candidate * 1_000L, 96) ?: continue
                            val useful = try { !VideoThumbnailRules.isBlank(preview) } finally { preview.recycle() }
                            if (!useful) continue
                            val full = frame(retriever, candidate * 1_000L, THUMBNAIL_SIDE) ?: continue
                            chosen = full
                            break
                        }
                    }
                }
                val parent = requireNotNull(target.parentFile)
                if (!parent.exists() && !parent.mkdirs()) return
                val temporary = File.createTempFile("thumb-", ".tmp", parent)
                try {
                    FileOutputStream(temporary).use { stream ->
                        check(chosen.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream))
                    }
                    if (temporary.length() > 0L && !temporary.renameTo(target)) {
                        throw IllegalStateException("Não foi possível salvar a miniatura")
                    }
                } finally {
                    temporary.delete()
                }
                parent.listFiles()?.forEach { other ->
                    if (other != target && other.extension == "jpg") other.delete()
                }
            } finally {
                if (chosen !== opening) chosen.recycle()
                opening.recycle()
            }
        } finally {
            retriever.release()
        }
    }

    private fun frame(retriever: MediaMetadataRetriever, micros: Long, maxSide: Int): Bitmap? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            val originalWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val originalHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (originalWidth <= 0 || originalHeight <= 0) return fullFrame(retriever, micros, maxSide)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val width = if (rotation % 180 != 0) originalHeight else originalWidth
            val height = if (rotation % 180 != 0) originalWidth else originalHeight
            val scale = minOf(1f, maxSide.toFloat() / maxOf(width, height).coerceAtLeast(1))
            val scaled = retriever.getScaledFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                (width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
            val expectedLongSide = minOf(maxSide, maxOf(width, height))
            if (scaled == null && maxOf(width, height) <= 1920) {
                fullFrame(retriever, micros, maxSide)
            } else if (maxSide >= 320 && maxOf(width, height) <= 1920 &&
                scaled != null && maxOf(scaled.width, scaled.height) < expectedLongSide * 7 / 10
            ) {
                fullFrame(retriever, micros, maxSide)?.also { scaled.recycle() } ?: scaled
            } else scaled
        } else {
            fullFrame(retriever, micros, maxSide)
        }
    }

    private fun fullFrame(retriever: MediaMetadataRetriever, micros: Long, maxSide: Int): Bitmap? {
        val original = retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) ?: return null
        val scale = minOf(1f, maxSide.toFloat() / maxOf(original.width, original.height))
        if (scale >= 1f) return original
        return try {
            Bitmap.createScaledBitmap(original, (original.width * scale).toInt().coerceAtLeast(1),
                (original.height * scale).toInt().coerceAtLeast(1), true)
        } finally {
            original.recycle()
        }
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
