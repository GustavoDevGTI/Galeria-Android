package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoRulesTest {
    @Test fun acceptsMp4FileTypeBox() {
        val bytes = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(12)
        assertTrue(MotionPhotoRules.isMp4Header(bytes, 4))
    }

    @Test fun rejectsTextOrInvalidBoxLength() {
        assertFalse(MotionPhotoRules.isMp4Header("ordinary photo".toByteArray(), 4))
        val bytes = byteArrayOf(0, 0, 0, 4) + "ftypisom".toByteArray()
        assertFalse(MotionPhotoRules.isMp4Header(bytes, 4))
    }

    @Test fun readsTheXmpPacketOfAJpegAndStopsAtTheImageData() {
        val xmp = "<x:xmpmeta GCamera:MotionPhoto=\"1\"/>"
        val head = jpeg(segment(0xE0, "JFIF\u0000".toByteArray()), segment(0xE1, xmpPayload(xmp)))
        assertEquals(xmp, MotionPhotoRules.xmpPacket(head))
        // XMP after the start of scan is image data, not metadata.
        val late = jpeg(segment(0xDA, ByteArray(4)), segment(0xE1, xmpPayload(xmp)))
        assertNull(MotionPhotoRules.xmpPacket(late))
        assertNull(MotionPhotoRules.xmpPacket("not a jpeg".toByteArray()))
    }

    @Test fun motionPhotoContainerPlacesTheClipFromTheEndOfTheFile() {
        val xmp = """
            <Container:Directory><rdf:Seq>
              <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="image/jpeg" Item:Semantic="Primary" Item:Length="0" Item:Padding="0"/></rdf:li>
              <rdf:li rdf:parseType="Resource"><Container:Item Item:Mime="video/mp4" Item:Semantic="MotionPhoto" Item:Length="5000" Item:Padding="0"/></rdf:li>
            </rdf:Seq></Container:Directory>
        """.trimIndent()
        assertEquals(MotionPhotoClip(15_000L, 5_000L), MotionPhotoRules.clipFromXmp(xmp, 20_000L))
    }

    @Test fun itemsAfterTheClipAndElementSyntaxAreCounted() {
        val xmp = """
            <rdf:li><GContainer:Item><GContainerItem:Semantic>Primary</GContainerItem:Semantic></GContainer:Item></rdf:li>
            <rdf:li><GContainer:Item><GContainerItem:Semantic>MotionPhoto</GContainerItem:Semantic><GContainerItem:Length>4000</GContainerItem:Length><GContainerItem:Padding>16</GContainerItem:Padding></GContainer:Item></rdf:li>
            <rdf:li><GContainer:Item><GContainerItem:Semantic>Depth</GContainerItem:Semantic><GContainerItem:Length>1000</GContainerItem:Length></GContainer:Item></rdf:li>
        """.trimIndent()
        assertEquals(MotionPhotoClip(10_000L - 5_016L, 4_000L), MotionPhotoRules.clipFromXmp(xmp, 10_000L))
    }

    @Test fun microVideoOffsetIsTheDistanceFromTheEnd() {
        val xmp = "<rdf:Description GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"3000\"/>"
        assertEquals(MotionPhotoClip(7_000L, 3_000L), MotionPhotoRules.clipFromXmp(xmp, 10_000L))
        assertNull(MotionPhotoRules.clipFromXmp(xmp.replace("MicroVideo=\"1\"", "MicroVideo=\"0\""), 10_000L))
        assertNull(MotionPhotoRules.clipFromXmp("<rdf:Description GCamera:MotionPhoto=\"1\"/>", 10_000L))
    }

    @Test fun samsungTrailerNamesTheClipEntry() {
        val name = "MotionPhoto_Data".toByteArray()
        val entryLength = 8L + name.size + 2_000L
        val directory = "SEFH".toByteArray() + le(106) + le(1) +
            byteArrayOf(0, 0, 0x30, 0x0A) + le(entryLength.toInt() + 40) + le(entryLength.toInt())
        val trailer = le(directory.size) + "SEFT".toByteArray()
        assertEquals(directory.size, MotionPhotoRules.sefDirectoryLength(trailer))
        assertNull(MotionPhotoRules.sefDirectoryLength(le(20) + "NOPE".toByteArray()))

        val entries = MotionPhotoRules.sefEntries(directory)
        assertEquals(listOf(MotionPhotoRules.SefEntry(entryLength + 40, entryLength)), entries)
        val header = byteArrayOf(0, 0, 0x30, 0x0A) + le(name.size) + name
        assertEquals(MotionPhotoClip(100L + 8 + name.size, 2_000L), MotionPhotoRules.sefClip(100L, header, entryLength))
        assertNull(MotionPhotoRules.sefClip(100L, byteArrayOf(0, 0, 0, 0) + le(5) + "Image".toByteArray(), entryLength))
    }

    @Test fun neighboursStartWithTheNextPhotoAndStayInsideTheQueue() {
        assertEquals(listOf(6, 4, 7), MotionPhotoPrefetchRules.neighbours(5, 10))
        assertEquals(listOf(1, 2), MotionPhotoPrefetchRules.neighbours(0, 10))
        assertEquals(listOf(8), MotionPhotoPrefetchRules.neighbours(9, 10))
        assertEquals(emptyList<Int>(), MotionPhotoPrefetchRules.neighbours(0, 1))
    }

    @Test fun clipCoversThePhotoOrSitsCentredInsideIt() {
        val photo = MotionPhotoInlineRules.Box(0f, 100f, 400f, 400f) // 4:3 on screen
        assertBox(photo, MotionPhotoInlineRules.videoBox(photo, 1440, 1080))
        // A 16:9 clip on a 4:3 photo: full width, centred vertically, photo visible above and below.
        assertBox(MotionPhotoInlineRules.Box(0f, 137.5f, 400f, 362.5f), MotionPhotoInlineRules.videoBox(photo, 1920, 1080))
        // Zoomed photo larger than the screen: the clip follows it.
        val zoomed = MotionPhotoInlineRules.Box(-400f, -200f, 800f, 700f)
        assertBox(zoomed, MotionPhotoInlineRules.videoBox(zoomed, 1440, 1080))
        // Unknown size yet: use the photo's rectangle.
        assertBox(photo, MotionPhotoInlineRules.videoBox(photo, 0, 0))
    }

    private fun assertBox(expected: MotionPhotoInlineRules.Box, actual: MotionPhotoInlineRules.Box) {
        assertEquals(expected.left, actual.left, 0.01f); assertEquals(expected.top, actual.top, 0.01f)
        assertEquals(expected.right, actual.right, 0.01f); assertEquals(expected.bottom, actual.bottom, 0.01f)
    }

    private fun le(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())
    private fun xmpPayload(xmp: String) = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray()
    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }
    private fun jpeg(vararg segments: ByteArray) =
        segments.fold(byteArrayOf(0xFF.toByte(), 0xD8.toByte())) { all, part -> all + part }
}
