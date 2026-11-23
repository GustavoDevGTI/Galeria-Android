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
    private const val HEAD_BYTES = 256 * 1024

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
                // Fast path: the camera's own record of where the video sits (XMP or Samsung's
                // trailer). Only when neither is there does the slower backward scan run.
                fastClip(channel, size)?.let { completed = true; return@use it }
                if (!isRelevant()) return@use null
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

    /** Reads only the head (XMP) and the last bytes (Samsung trailer); null when neither names a clip. */
    private fun fastClip(channel: java.nio.channels.FileChannel, size: Long): MotionPhotoClip? = runCatching {
        fun read(position: Long, length: Int): ByteArray? {
            if (position < 0L || length <= 0 || position + length > size) return null
            val buffer = ByteBuffer.allocate(length)
            channel.position(position)
            while (buffer.hasRemaining()) if (channel.read(buffer) <= 0) return null
            return buffer.array()
        }
        fun valid(clip: MotionPhotoClip?): MotionPhotoClip? = clip?.takeIf {
            it.offset > 0L && it.length in 16L..MAX_CLIP_BYTES && it.offset + it.length <= size &&
                read(it.offset, 12)?.let { header -> MotionPhotoRules.isMp4Header(header, 4) } == true
        }
        val head = read(0L, min(size, HEAD_BYTES.toLong()).toInt())
        head?.let(MotionPhotoRules::xmpPacket)?.let { xmp -> valid(MotionPhotoRules.clipFromXmp(xmp, size)) }?.let { return@runCatching it }
        val trailer = read(size - 8L, 8) ?: return@runCatching null
        val directoryLength = MotionPhotoRules.sefDirectoryLength(trailer) ?: return@runCatching null
        val directoryStart = size - 8L - directoryLength
        val directory = read(directoryStart, directoryLength) ?: return@runCatching null
        MotionPhotoRules.sefEntries(directory).forEach { entry ->
            val position = directoryStart - entry.offset
            val header = read(position, min(entry.length, 64L).toInt()) ?: return@forEach
            valid(MotionPhotoRules.sefClip(position, header, entry.length))?.let { return@runCatching it }
        }
        null
    }.getOrNull()

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
    data class SefEntry(val offset: Long, val length: Long)

    private const val XMP_NAMESPACE = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val SAMSUNG_CLIP_NAME = "MotionPhoto_Data"

    /** The XMP packet of a JPEG (APP1 segment), read from its first bytes; null for other formats. */
    fun xmpPacket(head: ByteArray): String? {
        if (head.size < 4 || head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte()) return null
        val namespace = XMP_NAMESPACE.toByteArray(Charsets.ISO_8859_1)
        var index = 2
        while (index + 4 <= head.size) {
            if (head[index] != 0xFF.toByte()) return null
            val marker = head[index + 1].toInt() and 0xFF
            if (marker == 0xFF) { index++; continue }
            if (marker == 0xDA || marker == 0xD9) return null
            val length = ((head[index + 2].toInt() and 0xFF) shl 8) or (head[index + 3].toInt() and 0xFF)
            if (length < 2) return null
            val payload = index + 4
            val end = index + 2 + length
            if (marker == 0xE1 && payload + namespace.size <= head.size &&
                (namespace.indices).all { head[payload + it] == namespace[it] }) {
                val from = payload + namespace.size
                return String(head, from, (minOf(end, head.size) - from).coerceAtLeast(0), Charsets.UTF_8)
            }
            index = end
        }
        return null
    }

    /**
     * Where the camera says the clip is. Motion Photo (Google, recent Samsung) lists the items
     * appended after the photo, in file order; the older MicroVideo format gives the clip's
     * distance from the end of the file.
     */
    fun clipFromXmp(xmp: String, fileSize: Long): MotionPhotoClip? {
        // One directory entry per <rdf:li>; the prefix is "Item:" (or "GContainerItem:" in older files).
        val items = xmp.split("<rdf:li").drop(1).mapNotNull { chunk ->
            val semantic = value(chunk, "Item:Semantic") ?: return@mapNotNull null
            ContainerItem(semantic, value(chunk, "Item:Length")?.toLongOrNull(),
                value(chunk, "Item:Padding")?.toLongOrNull() ?: 0L)
        }
        val video = items.indexOfFirst { it.semantic == "MotionPhoto" }
        if (video > 0) {
            // Padding follows its item, so everything from the clip to the end of the file is
            // the clip and the items (and paddings) after it.
            val after = items.subList(video, items.size)
            if (after.all { it.length != null }) {
                val trailing = after.sumOf { it.length!! + it.padding }
                val length = items[video].length!!
                if (length > 0L && trailing in 1L..fileSize) return MotionPhotoClip(fileSize - trailing, length)
            }
        }
        val microOffset = value(xmp, "GCamera:MicroVideoOffset")?.toLongOrNull()
        if (microOffset != null && microOffset in 1L until fileSize &&
            (value(xmp, "GCamera:MicroVideo") ?: "1") != "0") {
            return MotionPhotoClip(fileSize - microOffset, microOffset)
        }
        return null
    }

    /** Samsung ends the file with a directory ("SEFH" … "SEFT"); returns its length, or null. */
    fun sefDirectoryLength(lastEight: ByteArray): Int? {
        if (lastEight.size != 8 || String(lastEight, 4, 4, Charsets.ISO_8859_1) != "SEFT") return null
        return littleEndian(lastEight, 0).toInt().takeIf { it in 12..(1 shl 20) }
    }

    fun sefEntries(directory: ByteArray): List<SefEntry> {
        if (directory.size < 12 || String(directory, 0, 4, Charsets.ISO_8859_1) != "SEFH") return emptyList()
        val count = littleEndian(directory, 8).toInt()
        if (count !in 1..256) return emptyList()
        return (0 until count).mapNotNull { index ->
            val at = 12 + index * 12
            if (at + 12 > directory.size) return@mapNotNull null
            SefEntry(littleEndian(directory, at + 4), littleEndian(directory, at + 8)).takeIf { it.offset > 0L && it.length > 0L }
        }
    }

    /** An entry's header is 2+2 bytes of type, the name length and the name; the clip follows it. */
    fun sefClip(position: Long, header: ByteArray, entryLength: Long): MotionPhotoClip? {
        if (header.size < 8) return null
        val nameLength = littleEndian(header, 4).toInt()
        if (nameLength != SAMSUNG_CLIP_NAME.length || 8 + nameLength > header.size) return null
        if (String(header, 8, nameLength, Charsets.ISO_8859_1) != SAMSUNG_CLIP_NAME) return null
        val skip = 8L + nameLength
        return MotionPhotoClip(position + skip, entryLength - skip).takeIf { it.length > 0L }
    }

    private data class ContainerItem(val semantic: String, val length: Long?, val padding: Long)

    /** An attribute or element value; "Item:X" also matches prefixed forms such as "GContainerItem:X". */
    private fun value(xml: String, name: String): String? {
        val pattern = (if (name.startsWith("Item:")) "[A-Za-z]*" else "\\b") + Regex.escape(name)
        return Regex("(?<![A-Za-z:])$pattern\\s*=\\s*\"([^\"]*)\"").find(xml)?.groupValues?.get(1)
            ?: Regex("<($pattern)>([^<]*)</\\1>").find(xml)?.groupValues?.get(2)?.trim()
    }

    private fun littleEndian(bytes: ByteArray, at: Int): Long =
        (0..3).fold(0L) { value, position -> value or ((bytes[at + position].toLong() and 0xffL) shl (8 * position)) }

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

internal object MotionPhotoPrefetchRules {
    /** The next photo first (the usual swipe), then the previous one, then the one after next. */
    fun neighbours(index: Int, size: Int): List<Int> =
        listOf(index + 1, index - 1, index + 2).filter { it in 0 until size && it != index }
}
