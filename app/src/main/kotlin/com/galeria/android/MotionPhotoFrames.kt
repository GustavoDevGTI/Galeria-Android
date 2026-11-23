package com.galeria.android

/**
 * Layout of a Motion Photo whose highlighted frame was chosen in the Galeria:
 *
 *     [chosen frame, JPEG] [original photo, JPEG] [tail: clip and any camera data after it]
 *
 * The tail is kept byte for byte, so every reader that finds the clip from the end of the file
 * (Motion Photo and MicroVideo XMP, Samsung's trailer) still finds it. The chosen frame carries
 * the original XMP plus three Galeria fields giving the original photo's and the tail's lengths
 * (both from the end of the file) and the chosen time. Choosing again replaces only the first
 * part; going back to the original writes [original photo][tail], exactly the camera's file.
 */
internal object MotionFrameRules {
    const val NAMESPACE = "http://ns.galeria.android/motion/1.0/"
    private const val PREFIX = "GaleriaMotion"
    private const val XMP_HEADER = "http://ns.adobe.com/xap/1.0/\u0000"
    private const val PRESENTATION = "GCamera:MotionPhotoPresentationTimestampUs"

    data class Layout(
        val originalStart: Int,
        val originalEnd: Int,
        val tailStart: Int,
        /** True when the photo shown is a frame chosen here (the original is stored behind it). */
        val edited: Boolean,
        /** The chosen frame, or the camera's own highlighted frame, in microseconds. */
        val frameTimeUs: Long?
    ) {
        val originalLength get() = originalEnd - originalStart
    }

    /** Index just after the JPEG's end-of-image marker, following its segments and scans. */
    fun jpegEnd(bytes: ByteArray, from: Int = 0): Int? {
        if (from + 4 > bytes.size || bytes[from] != 0xFF.toByte() || bytes[from + 1] != 0xD8.toByte()) return null
        var index = from + 2
        while (index + 1 < bytes.size) {
            if (bytes[index] != 0xFF.toByte()) return null
            val marker = bytes[index + 1].toInt() and 0xFF
            when {
                marker == 0xFF -> { index++; continue }
                marker == 0xD9 -> return index + 2
                marker == 0x01 || marker in 0xD0..0xD7 -> { index += 2; continue }
            }
            if (index + 4 > bytes.size) return null
            val length = u16(bytes, index + 2)
            if (length < 2) return null
            index += 2 + length
            if (marker != 0xDA) continue
            // Entropy-coded data: a marker is 0xFF followed by anything but 0x00 (stuffing),
            // a restart marker or another 0xFF (fill).
            while (index + 1 < bytes.size) {
                if (bytes[index] == 0xFF.toByte()) {
                    val next = bytes[index + 1].toInt() and 0xFF
                    if (next == 0x00 || next in 0xD0..0xD7 || next == 0xFF) { index += if (next == 0xFF) 1 else 2; continue }
                    break
                }
                index++
            }
        }
        return null
    }

    fun layout(bytes: ByteArray): Layout? {
        val primaryEnd = jpegEnd(bytes) ?: return null
        val xmp = MotionPhotoRules.xmpPacket(bytes.copyOfRange(0, minOf(bytes.size, primaryEnd)))
        val originalLength = galeriaValue(xmp, "OriginalLength")?.toLongOrNull()
        val tailLength = galeriaValue(xmp, "TailLength")?.toLongOrNull()
        if (originalLength != null && tailLength != null) {
            val tailStart = bytes.size.toLong() - tailLength
            val originalStart = tailStart - originalLength
            if (originalLength > 0 && tailLength >= 0 && originalStart >= primaryEnd &&
                jpegEnd(bytes, originalStart.toInt()) != null) {
                return Layout(originalStart.toInt(), tailStart.toInt(), tailStart.toInt(), true,
                    galeriaValue(xmp, "FrameTimeUs")?.toLongOrNull())
            }
        }
        return Layout(0, primaryEnd, primaryEnd, false, presentationTimeUs(xmp))
    }

    /** The camera's highlighted frame, when it recorded one. */
    fun presentationTimeUs(xmp: String?): Long? =
        xmp?.let { Regex("$PRESENTATION\\s*=\\s*\"(-?\\d+)\"").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            ?.takeIf { it >= 0L }

    /** The original photo's XMP with the Galeria fields set and the highlighted time updated. */
    fun editedXmp(source: String?, originalLength: Int, tailLength: Int, frameTimeUs: Long): String {
        val fields = " xmlns:$PREFIX=\"$NAMESPACE\" $PREFIX:OriginalLength=\"$originalLength\" " +
            "$PREFIX:TailLength=\"$tailLength\" $PREFIX:FrameTimeUs=\"$frameTimeUs\""
        val cleaned = source
            ?.replace(Regex("\\s+xmlns:$PREFIX=\"[^\"]*\""), "")
            ?.replace(Regex("\\s+$PREFIX:\\w+=\"[^\"]*\""), "")
            ?.replace(Regex("($PRESENTATION\\s*=\\s*\")-?\\d+(\")"), "$1$frameTimeUs$2")
        val at = cleaned?.indexOf("<rdf:Description") ?: -1
        if (cleaned != null && at >= 0) {
            val insert = at + "<rdf:Description".length
            return cleaned.substring(0, insert) + fields + cleaned.substring(insert)
        }
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
            "<rdf:Description rdf:about=\"\"$fields/></rdf:RDF></x:xmpmeta>"
    }

    /** The JPEG with [xmp] as its only XMP segment, placed after the leading APP0/Exif segments. */
    fun withXmp(jpeg: ByteArray, xmp: String): ByteArray {
        require(jpeg.size >= 4 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte()) { "Não é JPEG" }
        val payload = XMP_HEADER.toByteArray(Charsets.ISO_8859_1) + xmp.toByteArray(Charsets.UTF_8)
        require(payload.size + 2 <= 0xFFFF) { "XMP grande demais" }
        val out = java.io.ByteArrayOutputStream(jpeg.size + payload.size + 4)
        out.write(jpeg, 0, 2)
        var index = 2
        while (index + 4 <= jpeg.size && jpeg[index] == 0xFF.toByte()) {
            val marker = jpeg[index + 1].toInt() and 0xFF
            if (marker !in 0xE0..0xEF) break
            val end = index + 2 + u16(jpeg, index + 2)
            if (end > jpeg.size) break
            val isXmp = marker == 0xE1 && startsWith(jpeg, index + 4, XMP_HEADER)
            if (!isXmp) out.write(jpeg, index, end - index)
            index = end
        }
        val length = payload.size + 2
        out.write(byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()))
        out.write(payload)
        out.write(jpeg, index, jpeg.size - index)
        return out.toByteArray()
    }

    /** A new file showing [frame] (a JPEG), keeping the original photo and the tail of [source]. */
    fun withChosenFrame(source: ByteArray, layout: Layout, frame: ByteArray, frameTimeUs: Long): ByteArray {
        val original = source.copyOfRange(layout.originalStart, layout.originalEnd)
        val tail = source.copyOfRange(layout.tailStart, source.size)
        val xmp = editedXmp(MotionPhotoRules.xmpPacket(original), original.size, tail.size, frameTimeUs)
        return withXmp(frame, xmp) + original + tail
    }

    /** The camera's file again: the original photo followed by the tail. */
    fun restored(source: ByteArray, layout: Layout): ByteArray =
        source.copyOfRange(layout.originalStart, layout.originalEnd) + source.copyOfRange(layout.tailStart, source.size)

    private fun galeriaValue(xmp: String?, name: String): String? =
        xmp?.let { Regex("$PREFIX:$name\\s*=\\s*\"([^\"]*)\"").find(it)?.groupValues?.get(1) }

    private fun startsWith(bytes: ByteArray, at: Int, text: String): Boolean {
        val expected = text.toByteArray(Charsets.ISO_8859_1)
        return at + expected.size <= bytes.size && expected.indices.all { bytes[at + it] == expected[it] }
    }

    private fun u16(bytes: ByteArray, at: Int) = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}
