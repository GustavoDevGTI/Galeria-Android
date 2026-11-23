package com.galeria.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionFrameRulesTest {
    private val cameraXmp = """<x:xmpmeta><rdf:RDF><rdf:Description rdf:about="" GCamera:MotionPhoto="1" GCamera:MotionPhotoPresentationTimestampUs="733000">
        <Container:Directory><rdf:Seq>
          <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
          <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="40" Item:Padding="0"/></rdf:li>
        </rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
    private val clip = ByteArray(40) { (it + 1).toByte() }

    @Test fun endOfImageSkipsSegmentsStuffingAndRestartMarkers() {
        val photo = jpeg(xmp = null, fill = 7)
        // An FF D9 inside a metadata segment is not the end of the image.
        val tricky = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) + segment(0xE2, byteArrayOf(0xFF.toByte(), 0xD9.toByte(), 1, 2)) +
            photo.copyOfRange(2, photo.size)
        assertEquals(photo.size, MotionFrameRules.jpegEnd(photo))
        assertEquals(tricky.size, MotionFrameRules.jpegEnd(tricky + clip))
        assertNull(MotionFrameRules.jpegEnd("not a jpeg".toByteArray()))
    }

    @Test fun cameraFileIsTheOriginalPhotoFollowedByTheTail() {
        val photo = jpeg(cameraXmp, fill = 3)
        val file = photo + clip
        val layout = MotionFrameRules.layout(file)!!
        assertFalse(layout.edited)
        assertEquals(0, layout.originalStart)
        assertEquals(photo.size, layout.originalEnd)
        assertEquals(photo.size, layout.tailStart)
        assertEquals(733_000L, layout.frameTimeUs)
    }

    @Test fun choosingAFrameKeepsTheOriginalAndTheClipAndCanBeUndone() {
        val photo = jpeg(cameraXmp, fill = 3)
        val camera = photo + clip
        val frame = jpeg(xmp = null, fill = 9)
        val edited = MotionFrameRules.withChosenFrame(camera, MotionFrameRules.layout(camera)!!, frame, 1_250_000L)

        val layout = MotionFrameRules.layout(edited)!!
        assertTrue(layout.edited)
        assertEquals(1_250_000L, layout.frameTimeUs)
        assertArrayEquals(photo, edited.copyOfRange(layout.originalStart, layout.originalEnd))
        assertArrayEquals(clip, edited.copyOfRange(layout.tailStart, edited.size))
        // The shown photo carries the camera's XMP, so the clip is still found from the end of the file.
        val xmp = MotionPhotoRules.xmpPacket(edited)!!
        assertTrue(xmp.contains("GCamera:MotionPhotoPresentationTimestampUs=\"1250000\""))
        assertEquals(MotionPhotoClip(edited.size - 40L, 40L), MotionPhotoRules.clipFromXmp(xmp, edited.size.toLong()))
        assertEquals(1, Regex("http://ns.adobe.com/xap/1.0/").findAll(String(edited.copyOfRange(0, layout.originalStart), Charsets.ISO_8859_1)).count())

        // Choosing again replaces only the shown frame; the stored original stays the camera's.
        val again = MotionFrameRules.withChosenFrame(edited, layout, jpeg(xmp = null, fill = 5), 400_000L)
        val second = MotionFrameRules.layout(again)!!
        assertEquals(400_000L, second.frameTimeUs)
        assertArrayEquals(photo, again.copyOfRange(second.originalStart, second.originalEnd))

        // Going back writes exactly the camera's file.
        assertArrayEquals(camera, MotionFrameRules.restored(again, second))
    }

    @Test fun photoWithoutXmpGetsAMinimalPacket() {
        val camera = jpeg(xmp = null, fill = 2) + clip
        val edited = MotionFrameRules.withChosenFrame(camera, MotionFrameRules.layout(camera)!!, jpeg(xmp = null, fill = 4), 10L)
        val layout = MotionFrameRules.layout(edited)!!
        assertTrue(layout.edited)
        assertEquals(10L, layout.frameTimeUs)
        assertArrayEquals(camera, MotionFrameRules.restored(edited, layout))
        assertNull(MotionFrameRules.presentationTimeUs("<rdf:Description/>"))
    }

    /** A tiny JPEG-shaped byte stream: SOI, optional XMP, a table, one scan with stuffing and a restart, EOI. */
    private fun jpeg(xmp: String?, fill: Int): ByteArray {
        var bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        if (xmp != null) bytes += segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray())
        bytes += segment(0xDB, ByteArray(5) { fill.toByte() })
        bytes += segment(0xDA, byteArrayOf(1, 2, 3))
        bytes += byteArrayOf(fill.toByte(), 0xFF.toByte(), 0x00, 0x11, 0xFF.toByte(), 0xD0.toByte(), 0x22, fill.toByte())
        bytes += byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        return bytes
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }
}
