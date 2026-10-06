package com.galeria.android

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import android.provider.MediaStore
import java.security.MessageDigest
import androidx.core.content.edit
import kotlin.math.min

internal data class MotionPhotoClip(val offset: Long, val length: Long)

internal object MotionPhotoSupport {
    private const val MAX_SCAN_BYTES = 64L * 1024L * 1024L
    private const val MAX_CLIP_BYTES = 128L * 1024L * 1024L
    private const val MAX_CACHE_BYTES = 256L * 1024L * 1024L
    private const val BLOCK_BYTES = 64 * 1024

    fun detect(context: Context, uri: Uri, isRelevant: () -> Boolean = { true }): MotionPhotoClip? {
        if (!isRelevant()) return null
        val key = detectionKey(context, uri)
        val preferences = context.getSharedPreferences("motion_photo_detection_v1", Context.MODE_PRIVATE)
        if (key != null && preferences.contains(key)) {
            val cached = preferences.getString(key, "none").orEmpty().split(':')
            return if (isRelevant() && cached.size == 2) MotionPhotoClip(cached[0].toLong(), cached[1].toLong()) else null
        }
        var completed = false
        val clip = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                val size = channel.size()
                if (size < 16L) { completed = true; return@use null }
                var end = size
                val earliest = (size - MAX_SCAN_BYTES).coerceAtLeast(0L)
                val buffer = ByteBuffer.allocate(BLOCK_BYTES + 8)
                while (end > earliest) {
                    if (!isRelevant()) return@use null
                    val start = (end - BLOCK_BYTES - 8L).coerceAtLeast(earliest)
                    buffer.clear()
                    buffer.limit((end - start).toInt())
                    channel.position(start)
                    while (buffer.hasRemaining()) {
                        if (!isRelevant()) return@use null
                        if (channel.read(buffer) <= 0) break
                    }
                    val bytes = buffer.array()
                    for (index in buffer.position() - 8 downTo 4) {
                        if (!MotionPhotoRules.isMp4Header(bytes, index)) continue
                        if (!isRelevant()) return@use null
                        val offset = start + index - 4
                        val length = size - offset
                        if (offset <= 0L || length > MAX_CLIP_BYTES) continue
                        val video = MediaMetadataRetriever()
                        val valid = try {
                            video.setDataSource(descriptor.fileDescriptor, offset, length)
                            video.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                        } catch (_: Exception) {
                            false
                        } finally {
                            video.release()
                        }
                        if (valid) { completed = true; return@use MotionPhotoClip(offset, length) }
                    }
                    if (start == earliest) break
                    end = start + 8L
                }
                completed = true
                null
            }
        }
        }.getOrNull()
        if (completed && isRelevant() && key != null) {
            val entries = preferences.all.keys
            preferences.edit {
                if (entries.size >= 512) entries.take(entries.size - 511).forEach { remove(it) }
                putString(key, clip?.let { "${it.offset}:${it.length}" } ?: "none")
            }
        }
        return clip.takeIf { isRelevant() }
    }

    private fun detectionKey(context: Context, uri: Uri): String? = runCatching {
        val attributes = if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path))
            if (!file.isFile) return null
            "${file.length()}:${file.lastModified()}"
        } else {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(1)) return null
                    "${cursor.getLong(0)}:${cursor.getLong(1)}"
                } ?: return null
        }
        val source = "$uri:$attributes:${MediaContentRevision.key(context, uri)}"
        MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }.getOrNull()

    fun cachedClip(context: Context, uri: Uri, clip: MotionPhotoClip): File {
        val folder = File(context.cacheDir, "motion_photos").apply { mkdirs() }
        val target = File(folder, "${uri.toString().hashCode()}_${clip.offset}_${clip.length}.mp4")
        if (target.length() == clip.length) {
            target.setLastModified(System.currentTimeMillis())
            trimCache(folder, target)
            return target
        }
        context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
            FileInputStream(descriptor.fileDescriptor).channel.use { input ->
                FileOutputStream(target).channel.use { output ->
                    var copied = 0L
                    while (copied < clip.length) {
                        val transferred = input.transferTo(clip.offset + copied, min(8L * 1024L * 1024L, clip.length - copied), output)
                        if (transferred <= 0L) error("Não foi possível ler o vídeo da foto em movimento.")
                        copied += transferred
                    }
                }
            }
        } ?: error("Não foi possível abrir a foto em movimento.")
        trimCache(folder, target)
        return target
    }

    private fun trimCache(folder: File, current: File) {
        var total = 0L
        folder.listFiles()
            ?.filter { it.isFile && it.extension == "mp4" }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { file ->
                total += file.length()
                if (total > MAX_CACHE_BYTES && file != current) {
                    total -= file.length()
                    file.delete()
                }
            }
    }
}

internal object MotionPhotoRules {
    fun isMp4Header(bytes: ByteArray, index: Int): Boolean {
        if (index < 4 || index + 8 > bytes.size) return false
        val boxLength = (0..3).fold(0L) { value, position ->
            (value shl 8) or (bytes[index - 4 + position].toLong() and 0xffL)
        }
        return boxLength in 12L..128L &&
            bytes[index] == 'f'.code.toByte() &&
            bytes[index + 1] == 't'.code.toByte() &&
            bytes[index + 2] == 'y'.code.toByte() &&
            bytes[index + 3] == 'p'.code.toByte() &&
            bytes[index + 4].toInt() in 32..126 &&
            bytes[index + 5].toInt() in 32..126 &&
            bytes[index + 6].toInt() in 32..126 &&
            bytes[index + 7].toInt() in 32..126
    }
}
