package com.galeria.android

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

internal object VideoTimelineRules {
    const val FRAME_INTERVAL_MS = 1000L

    fun frameCount(durationMs: Long): Long =
        if (durationMs <= 0L) 0L else 1L + durationMs / FRAME_INTERVAL_MS +
            if (durationMs % FRAME_INTERVAL_MS == 0L) 0L else 1L

    fun frameTime(index: Long, durationMs: Long): Long =
        (index.coerceAtLeast(0L) * FRAME_INTERVAL_MS)
            .coerceIn(0L, (durationMs - 1L).coerceAtLeast(0L))

    fun visibleIndices(positionMs: Long, durationMs: Long, width: Int, cellWidth: Float): LongRange {
        if (durationMs <= 0L || width <= 0 || cellWidth <= 0f) return LongRange.EMPTY
        val center = positionMs.coerceIn(0L, durationMs).toDouble() / FRAME_INTERVAL_MS
        val halfVisible = width / (2.0 * cellWidth)
        val last = frameCount(durationMs) - 1L
        return (floor(center - halfVisible).toLong() - 1L).coerceIn(0L, last)..
            (ceil(center + halfVisible).toLong() + 1L).coerceIn(0L, last)
    }

    fun positionAfterDrag(startMs: Long, distancePixels: Float, cellWidth: Float, durationMs: Long): Long =
        (startMs + (distancePixels.toDouble() / cellWidth.coerceAtLeast(1f) * FRAME_INTERVAL_MS).roundToLong())
            .coerceIn(0L, durationMs.coerceAtLeast(0L))

    fun progressPosition(progress: Int, maximum: Int, durationMs: Long): Long =
        (progress.coerceIn(0, maximum.coerceAtLeast(1)).toDouble() /
            maximum.coerceAtLeast(1) * durationMs.coerceAtLeast(0L)).roundToLong()
}
