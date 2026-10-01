package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.LruCache
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** One decoder at a time; only the latest visible window is queued. */
internal class VideoTimelineFrames(context: Context, private val uri: Uri, revision: String) {
    private val context = context.applicationContext
    private val identity = "$uri|$revision"
    @Volatile private var closed = false
    @Volatile private var wanted = emptyList<Long>()
    @Volatile private var requestVersion = 0L
    private var running = false
    private var retriever: MediaMetadataRetriever? = null // worker thread only
    private var sourceKey: String? = null
    private var writtenFrames = 0
    private val failed = HashSet<Long>()
    private var notify: ((Long, Bitmap) -> Unit)? = null
    private var notifyFailure: ((Long) -> Unit)? = null

    @Synchronized fun request(times: List<Long>, onFailure: ((Long) -> Unit)? = null, onFrame: (Long, Bitmap) -> Unit) {
        if (closed) return
        wanted = times.distinct()
        notify = onFrame
        notifyFailure = onFailure
        requestVersion++
        if (wanted.isEmpty() && !running) return
        if (running) return
        running = true
        worker.execute { drain() }
    }

    private fun drain() {
        val delivered = HashSet<Long>()
        var deliveredVersion = -1L
        try {
            if (closed) return
            val key = sourceKey ?: fingerprint().also { sourceKey = it; prune() }
            while (!closed) {
                val version = requestVersion
                if (version != deliveredVersion) {
                    delivered.clear()
                    deliveredVersion = version
                    wanted.filter { it in failed }.forEach { time -> reportFailure(time) }
                }
                val time = wanted.firstOrNull { it !in delivered && it !in failed } ?: break
                val cacheKey = "$key-$time"
                val bitmap = runCatching {
                    memory.get(cacheKey) ?: loadFrame(cacheKey, time)?.also { memory.put(cacheKey, it) }
                }.getOrNull()
                if (bitmap == null) { failed.add(time); reportFailure(time) } else {
                    delivered.add(time)
                    main.post { if (!closed && time in wanted) notify?.invoke(time, bitmap) }
                }
            }
        } catch (_: Exception) {
            failed.addAll(wanted)
            wanted.forEach { reportFailure(it) }
            deliveredVersion = requestVersion
        } finally {
            retriever?.let { runCatching { it.release() } }
            retriever = null
            synchronized(this) {
                running = false
                // A new viewport can arrive just as the loop ends.
                if (!closed && (deliveredVersion != requestVersion || wanted.any { it !in delivered && it !in failed })) {
                    notify?.let { request(wanted, notifyFailure, it) }
                }
            }
        }
    }

    private fun reportFailure(time: Long) {
        main.post { if (!closed && time in wanted) notifyFailure?.invoke(time) }
    }

    private fun fingerprint(): String {
        val attributes = if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            "${file.length()}|${file.lastModified()}"
        } else runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use {
                if (it.moveToFirst()) "${it.getLong(0)}|${it.getLong(1)}" else ""
            }.orEmpty()
        }.getOrDefault("")
        return MessageDigest.getInstance("SHA-256").digest("strip-v2|$identity|$attributes".toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }.take(32)
    }

    private fun loadFrame(key: String, timeMs: Long): Bitmap? {
        val directory = File(context.cacheDir, "video_timeline").apply { mkdirs() }
        val file = File(directory, "$key.jpg")
        BitmapFactory.decodeFile(file.path)?.let { file.setLastModified(System.currentTimeMillis()); return it }
        if (closed) return null
        val decoder = retriever ?: MediaMetadataRetriever().also {
            retriever = it
            it.setDataSource(context, uri)
        }
        val width = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 320
        val height = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 180
        val rotation = decoder.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val w = if (rotation % 180 == 0) width else height
        val h = if (rotation % 180 == 0) height else width
        val scale = minOf(1f, 320f / maxOf(w, h).coerceAtLeast(1))
        val targetWidth = (w * scale).toInt().coerceAtLeast(1)
        val targetHeight = (h * scale).toInt().coerceAtLeast(1)
        val frame = if (Build.VERSION.SDK_INT >= 27) {
            decoder.getScaledFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST, targetWidth, targetHeight)
        } else {
            decoder.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST)?.let { original ->
                Bitmap.createScaledBitmap(original, targetWidth, targetHeight, true).also {
                    if (it !== original) original.recycle()
                }
            }
        } ?: return null
        runCatching {
            val temporary = File.createTempFile("frame-", ".tmp", directory)
            try {
                temporary.outputStream().use { frame.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                temporary.renameTo(file)
            } finally { temporary.delete() }
            if (++writtenFrames % 24 == 0) prune()
        }
        return frame
    }

    private fun prune() {
        val files = File(context.cacheDir, "video_timeline").listFiles()?.sortedBy { it.lastModified() } ?: return
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (bytes <= 60L * 1024L * 1024L && count <= 480) break
            val size = file.length()
            if (file.delete()) { bytes -= size; count-- }
        }
    }

    fun close() { closed = true; wanted = emptyList(); notify = null; notifyFailure = null }

    companion object {
        private val worker = Executors.newSingleThreadExecutor { task ->
            Thread({
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                task.run()
            }, "video-timeline-frames")
        }
        private val main = Handler(Looper.getMainLooper())
        private val memory = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount
        }
    }
}
