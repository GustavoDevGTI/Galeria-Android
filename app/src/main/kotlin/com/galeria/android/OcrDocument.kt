package com.galeria.android

/** Coordinates are normalized to the EXIF-oriented original image, not the OCR
 * working bitmap or its temporary rotation. No Android objects or bitmap cache. */
internal data class OcrPoint(val x: Float, val y: Float)
internal data class OcrWord(val text: String, val corners: List<OcrPoint>, val line: Int, val block: Int)
internal data class OcrDocument(val text: String, val words: List<OcrWord> = emptyList()) {
    fun selectedText(first: Int, last: Int): String {
        if (words.isEmpty()) return ""
        val selected = words.subList(minOf(first, last).coerceIn(words.indices),
            maxOf(first, last).coerceIn(words.indices) + 1)
        return buildString {
            selected.forEachIndexed { index, word ->
                if (index > 0) append(when {
                    word.block != selected[index - 1].block -> "\n\n"
                    word.line != selected[index - 1].line -> "\n"
                    else -> " "
                })
                append(word.text)
            }
        }
    }
}

internal object OcrGeometry {
    fun contains(corners: FloatArray, x: Float, y: Float): Boolean {
        if (corners.size != 8) return false
        var inside = false
        var previous = 3
        for (index in 0..3) {
            val ix = corners[index * 2]; val iy = corners[index * 2 + 1]
            val px = corners[previous * 2]; val py = corners[previous * 2 + 1]
            if ((iy > y) != (py > y) && x < (px - ix) * (y - iy) / (py - iy) + ix) inside = !inside
            previous = index
        }
        return inside
    }
    fun fromRotated(x: Float, y: Float, width: Int, height: Int, rotation: Int): OcrPoint {
        require(width > 0 && height > 0)
        val point = when (rotation) {
            0 -> OcrPoint(x / width, y / height)
            90 -> OcrPoint(y / width, 1f - x / height)
            180 -> OcrPoint(1f - x / width, 1f - y / height)
            270 -> OcrPoint(1f - y / width, x / height)
            else -> error("Unsupported OCR rotation: $rotation")
        }
        return OcrPoint(point.x.coerceIn(0f, 1f), point.y.coerceIn(0f, 1f))
    }
}
