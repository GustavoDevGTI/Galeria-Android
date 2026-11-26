package com.galeria.android

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Geometry of the viewer's filmstrip: one picture per cell, [intervalMs] of video per cell, the
 * playhead fixed in the middle. The strip opens as an overview of the whole video and each press
 * of its button brings it closer ([zoomInterval]), down to one frame per cell.
 */
internal object VideoTimelineRules {
    const val FRAME_INTERVAL_MS = 1000L
    /** Closest level: about one frame of a 30 fps video per cell. */
    const val FRAME_BY_FRAME_MS = 33L
    /** Overview, one second per cell, frame by frame. */
    const val ZOOM_LEVELS = 3
    /** Overview steps: round values, so neighbouring pictures sit at clean times. */
    private val OVERVIEW_STEPS = longArrayOf(1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L, 60_000L,
        120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L, 3_600_000L)

    /**
     * Video per cell at [level]: 0 shows the whole video across the strip with few pictures (quick
     * to load), 1 one second per cell, 2 frame by frame.
     */
    fun zoomInterval(level: Int, durationMs: Long, width: Int, cellWidth: Float): Long = when (level.coerceIn(0, ZOOM_LEVELS - 1)) {
        0 -> {
            val cells = (width / cellWidth.coerceAtLeast(1f)).coerceAtLeast(1f)
            val wanted = (durationMs / cells).toLong()
            OVERVIEW_STEPS.firstOrNull { it >= wanted } ?: OVERVIEW_STEPS.last()
        }
        1 -> FRAME_INTERVAL_MS
        else -> FRAME_BY_FRAME_MS
    }

    fun frameCount(durationMs: Long, intervalMs: Long = FRAME_INTERVAL_MS): Long =
        if (durationMs <= 0L) 0L else 1L + durationMs / intervalMs +
            if (durationMs % intervalMs == 0L) 0L else 1L

    fun frameTime(index: Long, durationMs: Long, intervalMs: Long = FRAME_INTERVAL_MS): Long =
        (index.coerceAtLeast(0L) * intervalMs)
            .coerceIn(0L, (durationMs - 1L).coerceAtLeast(0L))

    fun visibleIndices(positionMs: Long, durationMs: Long, width: Int, cellWidth: Float, intervalMs: Long = FRAME_INTERVAL_MS): LongRange {
        if (durationMs <= 0L || width <= 0 || cellWidth <= 0f) return LongRange.EMPTY
        val center = positionMs.coerceIn(0L, durationMs).toDouble() / intervalMs
        val halfVisible = width / (2.0 * cellWidth)
        val last = frameCount(durationMs, intervalMs) - 1L
        return (floor(center - halfVisible).toLong() - 1L).coerceIn(0L, last)..
            (ceil(center + halfVisible).toLong() + 1L).coerceIn(0L, last)
    }

    fun positionAfterDrag(startMs: Long, distancePixels: Float, cellWidth: Float, durationMs: Long, intervalMs: Long = FRAME_INTERVAL_MS): Long =
        (startMs + (distancePixels.toDouble() / cellWidth.coerceAtLeast(1f) * intervalMs).roundToLong())
            .coerceIn(0L, durationMs.coerceAtLeast(0L))

    fun progressPosition(progress: Int, maximum: Int, durationMs: Long): Long =
        (progress.coerceIn(0, maximum.coerceAtLeast(1)).toDouble() /
            maximum.coerceAtLeast(1) * durationMs.coerceAtLeast(0L)).roundToLong()
}
