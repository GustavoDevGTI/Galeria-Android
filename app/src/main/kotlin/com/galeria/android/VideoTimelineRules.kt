package com.galeria.android

import kotlin.math.roundToLong

internal object VideoTimelineRules {
    fun positionAt(fraction: Double, startMs: Long, spanMs: Long, durationMs: Long): Long =
        (startMs + (fraction.coerceIn(0.0, 1.0) * spanMs).roundToLong())
            .coerceIn(0L, durationMs.coerceAtLeast(0L))

    fun samples(startMs: Long, spanMs: Long, durationMs: Long, count: Int): List<Long> {
        if (durationMs <= 0L) return emptyList()
        val size = count.coerceIn(2, 24)
        return List(size) { index ->
            // Quantization shares cached frames between adjacent fine-scrubbing windows.
            val time = positionAt(index.toDouble() / (size - 1), startMs, spanMs, durationMs)
            ((time / 250L) * 250L).coerceAtMost((durationMs - 1L).coerceAtLeast(0L))
        }
    }

    fun fineSpan(durationMs: Long): Long = durationMs.coerceIn(1L, 20_000L)

    fun windowStart(positionMs: Long, spanMs: Long, durationMs: Long): Long =
        (positionMs - spanMs / 2L).coerceIn(0L, (durationMs - spanMs).coerceAtLeast(0L))
}
