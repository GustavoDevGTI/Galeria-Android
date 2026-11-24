package com.galeria.android

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** An original photo kept inside a joined video. */
internal class EmbeddedPhoto(val clipIndex: Int, val mime: String, val bytes: ByteArray)

/**
 * Original photos are kept inside the joined video when the photo clock is on, so separating can
 * give them back intact. Each one is an extra top-level MP4 box of type `uuid`, which players
 * skip, after the boxes of the video itself.
 */
internal object JoinPhotoBoxes {
    private val ID = byteArrayOf(
        0x47, 0x61, 0x6C, 0x65, 0x72, 0x69, 0x61, 0x4A, 0x6F, 0x69, 0x6E, 0x46, 0x6F, 0x74, 0x6F, 0x31
    ) // "GaleriaJoinFoto1"
    private const val HEADER = 8 + 16

    fun append(file: File, clipIndex: Int, mime: String, bytes: ByteArray) {
        val mimeBytes = mime.toByteArray(Charsets.UTF_8)
        val payload = 4 + 2 + mimeBytes.size + bytes.size
        val size = HEADER + payload
        val buffer = ByteBuffer.allocate(HEADER + 4 + 2 + mimeBytes.size)
            .putInt(size).put("uuid".toByteArray(Charsets.US_ASCII)).put(ID)
            .putInt(clipIndex).putShort(mimeBytes.size.toShort()).put(mimeBytes)
        RandomAccessFile(file, "rw").use { out ->
            out.seek(out.length())
            out.write(buffer.array())
            out.write(bytes)
        }
    }

    /** The montage positions of the photos kept inside, without reading them. */
    fun indices(channel: FileChannel): Set<Int> = boxes(channel).mapTo(LinkedHashSet()) { it.first }

    fun read(channel: FileChannel, clipIndex: Int): EmbeddedPhoto? {
        val position = boxes(channel).firstOrNull { it.first == clipIndex }?.second ?: return null
        val head = ByteBuffer.allocate(HEADER + 6)
        channel.position(position)
        readFully(channel, head)
        head.flip()
        val size = head.int.toLong() and 0xFFFFFFFFL
        head.position(HEADER)
        head.int
        val mimeLength = head.short.toInt() and 0xFFFF
        val mime = ByteBuffer.allocate(mimeLength).also { readFully(channel, it) }.array().toString(Charsets.UTF_8)
        val bytes = ByteBuffer.allocate((size - HEADER - 6 - mimeLength).toInt()).also { readFully(channel, it) }.array()
        return EmbeddedPhoto(clipIndex, mime, bytes)
    }

    /** (clip index, box position) of every photo box, walking the top-level boxes. */
    private fun boxes(channel: FileChannel): List<Pair<Int, Long>> {
        val found = ArrayList<Pair<Int, Long>>()
        val length = channel.size()
        var position = 0L
        val head = ByteBuffer.allocate(HEADER + 4)
        while (position + 8 <= length) {
            head.clear()
            channel.position(position)
            if (channel.read(head) < 8) break
            head.flip()
            var size = head.int.toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also { head.get(it) }.toString(Charsets.US_ASCII)
            if (size == 1L) {
                if (head.remaining() < 8) break
                size = head.long
            } else if (size == 0L) {
                size = length - position
            }
            if (size < 8) break
            if (type == "uuid" && head.remaining() >= 20) {
                val id = ByteArray(16).also { head.get(it) }
                if (id.contentEquals(ID)) found += head.int to position
            }
            position += size
        }
        return found
    }

    private fun readFully(channel: FileChannel, buffer: ByteBuffer) {
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) break
    }
}
