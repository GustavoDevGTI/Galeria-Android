package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Test

class DetailPlaybackRulesTest {
    @Test
    fun completedVideoDoesNotKeepAResumePosition() {
        assertEquals(0L, DetailPlaybackRules.rememberedPosition(15_000L, 20_000L, true))
        assertEquals(0L, DetailPlaybackRules.rememberedPosition(19_300L, 20_000L, false))
    }

    @Test
    fun unfinishedVideoKeepsItsPosition() {
        assertEquals(8_500L, DetailPlaybackRules.rememberedPosition(8_500L, 20_000L, false))
    }

    @Test
    fun seekTargetIsLimitedToVideoBounds() {
        assertEquals(0L, DetailPlaybackRules.seekTarget(3_000L, -10_000L, 20_000L))
        assertEquals(20_000L, DetailPlaybackRules.seekTarget(18_000L, 10_000L, 20_000L))
        assertEquals(9_000L, DetailPlaybackRules.seekTarget(4_000L, 5_000L, 20_000L))
    }

    @Test
    fun timelineProgressUsesTheSameThousandStepScaleAsTheSeekBar() {
        assertEquals(250, DetailPlaybackRules.timelineProgress(5_000L, 20_000L))
        assertEquals(0, DetailPlaybackRules.timelineProgress(5_000L, 0L))
        assertEquals(1000, DetailPlaybackRules.timelineProgress(25_000L, 20_000L))
    }

    @Test
    fun speedLabelsPreserveTheExistingPortugueseDisplay() {
        assertEquals("0,5x", DetailPlaybackRules.speedLabel(0.5f))
        assertEquals("1x", DetailPlaybackRules.speedLabel(1f))
        assertEquals("1,5x", DetailPlaybackRules.speedLabel(1.5f))
        assertEquals("2x", DetailPlaybackRules.speedLabel(2f))
    }
}
