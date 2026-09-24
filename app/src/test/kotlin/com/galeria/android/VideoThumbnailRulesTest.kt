package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoThumbnailRulesTest {
    @Test
    fun darkOpeningUsesStableLaterCandidates() {
        val first = VideoThumbnailRules.candidateMillis(100_000L, "content://media/123")
        assertEquals(first, VideoThumbnailRules.candidateMillis(100_000L, "content://media/123"))
        assertTrue(first.isNotEmpty())
        assertTrue(first.all { it in 1 until 100_000L })
    }

    @Test
    fun colorfulOrBrightOpeningIsNotConsideredBlank() {
        assertTrue(VideoThumbnailRules.isBlankPixels(IntArray(64) { 0xff050505.toInt() }))
        assertFalse(VideoThumbnailRules.isBlankPixels(IntArray(64) { 0xffff0000.toInt() }))
        assertFalse(VideoThumbnailRules.isBlankPixels(IntArray(64) { 0xff777777.toInt() }))
    }
}
