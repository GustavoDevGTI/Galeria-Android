package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoTimelineRulesTest {
    @Test fun handlesStayOrderedAndRestoringTheEntireVideoClearsTheSelection() {
        assertEquals(2_000L..8_000L, VideoTimelineRules.selectedRange(2_000, 8_000, 10_000))
        assertEquals(7_900L..8_000L, VideoTimelineRules.moveRangeEdge(2_000L..8_000L, true, 9_000, 10_000))
        assertEquals(2_000L..2_100L, VideoTimelineRules.moveRangeEdge(2_000L..8_000L, false, 1_000, 10_000))
        assertEquals(null, VideoTimelineRules.selectedRange(-1, 20_000, 10_000))
        assertEquals(null, VideoTimelineRules.moveRangeEdge(0L..8_000L, false, 10_000, 10_000))
        assertEquals(null, VideoTimelineRules.selectedRange(0, 0, 0))
        assertEquals(null, VideoTimelineRules.selectedRange(0, 1, 1))
    }
    @Test fun theStripOpensAsAnOverviewAndTwoPressesReachFrameByFrame() {
        // A one-minute video on a strip about 8 pictures wide: the overview fits it in about one width.
        val overview = VideoTimelineRules.zoomInterval(0, 60_000L, 432, 54f)
        assertEquals(10_000L, overview)
        assertTrue(VideoTimelineRules.frameCount(60_000L, overview) <= 8)
        // A short video never goes below one second per picture in the overview.
        assertEquals(1_000L, VideoTimelineRules.zoomInterval(0, 3_000L, 432, 54f))
        assertEquals(1_000L, VideoTimelineRules.zoomInterval(1, 60_000L, 432, 54f))
        assertEquals(VideoTimelineRules.FRAME_BY_FRAME_MS, VideoTimelineRules.zoomInterval(2, 60_000L, 432, 54f))
        assertEquals(VideoTimelineRules.FRAME_BY_FRAME_MS, VideoTimelineRules.zoomInterval(9, 60_000L, 432, 54f))
        // Dragging one picture moves by that level's step.
        assertEquals(10_033L, VideoTimelineRules.positionAfterDrag(10_000L, 54f, 54f, 60_000L, VideoTimelineRules.FRAME_BY_FRAME_MS))
        assertEquals(20_000L, VideoTimelineRules.positionAfterDrag(10_000L, 54f, 54f, 60_000L, overview))
    }

    @Test fun longVideoHasContinuousStripButDecodesOnlyVisibleSeconds() {
        val duration = 10L * 60 * 60 * 1000
        assertEquals(36_001L, VideoTimelineRules.frameCount(duration))
        val indices = VideoTimelineRules.visibleIndices(18_123_000L, duration, 432, 54f).toList()
        assertTrue(indices.size <= 12)
        assertTrue(indices.contains(18_123L))
        assertTrue(indices.zipWithNext().all { it.second == it.first + 1 })
        assertEquals(18_124_000L, VideoTimelineRules.positionAfterDrag(18_123_000L, 54f, 54f, duration))
        assertEquals(18_123_500L, VideoTimelineRules.positionAfterDrag(18_123_000L, 27f, 54f, duration))
    }

    @Test fun shortMotionPhotoAndEndPointsRemainInBounds() {
        for (duration in listOf(1L, 750L, 2200L, 3_600_000L)) {
            assertEquals(0L, VideoTimelineRules.positionAfterDrag(0L, -100_000f, 54f, duration))
            assertEquals(duration, VideoTimelineRules.positionAfterDrag(duration, 100_000f, 54f, duration))
            val end = VideoTimelineRules.visibleIndices(duration, duration, 432, 54f)
            assertTrue(end.map { VideoTimelineRules.frameTime(it, duration) }.all { it in 0 until duration })
            assertEquals(duration - 1L, VideoTimelineRules.frameTime(end.last, duration))
        }
    }

    @Test fun scrollingAcrossWholeVideoDoesNotLeaveGaps() {
        val duration = 63_250L
        val seen = (0L..duration step 4_000L).flatMap {
            VideoTimelineRules.visibleIndices(it, duration, 432, 54f).toList()
        }.toSet()
        assertEquals((0L until VideoTimelineRules.frameCount(duration)).toSet(), seen)
    }

    @Test fun simpleBarCanJumpAcrossWholeVideo() {
        val duration = 7_200_000L
        assertEquals(0L, VideoTimelineRules.progressPosition(-1, 100_000, duration))
        assertEquals(3_600_000L, VideoTimelineRules.progressPosition(50_000, 100_000, duration))
        assertEquals(duration, VideoTimelineRules.progressPosition(100_001, 100_000, duration))
    }

    @Test fun emptyVideoAndUnmeasuredViewportHaveNoFrames() {
        assertEquals(0L, VideoTimelineRules.frameCount(0L))
        assertTrue(VideoTimelineRules.visibleIndices(0L, 0L, 432, 54f).isEmpty())
        assertTrue(VideoTimelineRules.visibleIndices(0L, 1000L, 0, 54f).isEmpty())
    }
}
