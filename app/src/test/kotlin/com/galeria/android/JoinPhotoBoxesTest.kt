package com.galeria.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

class JoinPhotoBoxesTest {
    @Test fun photosKeptAfterTheVideoBoxesComeBackIntact() {
        val file = File.createTempFile("join", ".mp4")
        try {
            // A tiny stand-in for the video: an ftyp box and a free box.
            file.writeBytes(box("ftyp", ByteArray(12)) + box("free", ByteArray(40)))
            val first = ByteArray(5_000) { (it % 251).toByte() }
            val second = byteArrayOf(1, 2, 3)
            JoinPhotoBoxes.append(file, 1, "image/heic", first)
            JoinPhotoBoxes.append(file, 4, "image/jpeg", second)
            RandomAccessFile(file, "r").use { input ->
                val channel = input.channel
                assertEquals(setOf(1, 4), JoinPhotoBoxes.indices(channel))
                val photo = JoinPhotoBoxes.read(channel, 1)!!
                assertEquals("image/heic", photo.mime)
                assertArrayEquals(first, photo.bytes)
                assertArrayEquals(second, JoinPhotoBoxes.read(channel, 4)!!.bytes)
                assertNull(JoinPhotoBoxes.read(channel, 2))
            }
        } finally {
            file.delete()
        }
    }

    @Test fun aVideoWithoutPhotosHasNone() {
        val file = File.createTempFile("plain", ".mp4")
        try {
            file.writeBytes(box("ftyp", ByteArray(12)) + box("uuid", ByteArray(30)))
            RandomAccessFile(file, "r").use { assertEquals(emptySet<Int>(), JoinPhotoBoxes.indices(it.channel)) }
        } finally {
            file.delete()
        }
    }

    private fun box(type: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.US_ASCII)).put(body).array()
}
