package com.galeria.android

import kotlin.math.sqrt

internal data class OcrReading(val text: String, val confidence: Float = 0f)

internal object OcrRules {
    val rotations = listOf(0, 90, 270, 180)
    private val tokens = Regex("[\\p{L}\\p{N}]+")

    fun score(reading: OcrReading): Double {
        val words = tokens.findAll(reading.text).map { it.value }.toList()
        val characters = words.sumOf { it.length }
        val completeWords = words.count { word -> word.count(Char::isLetter) >= 3 }
        val confidence = reading.confidence.takeIf { it.isFinite() && it > 0f }?.coerceIn(0f, 1f) ?: 0.75f
        return (characters + completeWords * 6) * (0.25 + confidence * 0.75)
    }

    fun best(first: OcrReading, next: OcrReading): OcrReading =
        if (score(next) > score(first)) next else first

    fun isReliable(reading: OcrReading): Boolean =
        tokens.findAll(reading.text).count { it.value.count(Char::isLetter) >= 3 } >= 4 &&
            score(reading) >= 60 && (reading.confidence == 0f || reading.confidence >= 0.65f)

    fun isDarkBackground(pixels: IntArray): Boolean {
        if (pixels.isEmpty()) return false
        val dark = pixels.count { pixel ->
            val red = pixel ushr 16 and 255
            val green = pixel ushr 8 and 255
            val blue = pixel and 255
            (red * 54 + green * 183 + blue * 19) / 256 < 112
        }
        return dark.toDouble() / pixels.size >= 0.60
    }

    fun targetSize(width: Int, height: Int, detailed: Boolean): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val maxSide = if (detailed) 4096 else 2560
        val maxPixels = if (detailed) 6_000_000.0 else 3_000_000.0
        val scale = minOf(1.0, maxSide.toDouble() / maxOf(width, height), sqrt(maxPixels / (width.toDouble() * height)))
        return maxOf(1, (width * scale).toInt()) to maxOf(1, (height * scale).toInt())
    }
}
