package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimelineRulesTest {
    @Test fun tenHourVideoNeedsOnlyVisibleThumbnailsButCanSeekEverySecond() {
        val duration = 10L * 60 * 60 * 1000
        val frames = VideoTimelineRules.samples(0L, duration, duration, 8)
        assertEquals(8, frames.size)
        assertEquals(0L, frames.first())
        assertTrue(frames.zipWithNext().all { it.first < it.second })
        assertTrue(frames.last() >= duration - 250L)
        val position = 18_123_000L
        val span = VideoTimelineRules.fineSpan(duration)
        val start = VideoTimelineRules.windowStart(position, span, duration)
        assertEquals(position, VideoTimelineRules.positionAt(0.5, start, span, duration))
        assertEquals(position + 1000L, VideoTimelineRules.positionAt(0.55, start, span, duration))
    }

    @Test fun shortMotionPhotoAndEndPointsRemainInBounds() {
        for (duration in listOf(1L, 750L, 2200L, 3_600_000L)) {
            assertEquals(0L, VideoTimelineRules.positionAt(-0.2, 0L, duration, duration))
            assertEquals(duration, VideoTimelineRules.positionAt(1.2, 0L, duration, duration))
            assertTrue(VideoTimelineRules.samples(0L, duration, duration, 1000).all { it in 0 until duration })
            val span = VideoTimelineRules.fineSpan(duration)
            assertEquals((duration - span).coerceAtLeast(0L), VideoTimelineRules.windowStart(duration, span, duration))
        }
    }
}
