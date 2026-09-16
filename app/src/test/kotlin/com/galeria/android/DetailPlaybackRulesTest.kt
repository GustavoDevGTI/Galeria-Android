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
    fun recentSavedPositionCanBeRestoredForUpToTwelveHours() {
        val now = 200_000_000L

        assertEquals(
            8_500L,
            DetailPlaybackRules.restoredPosition(
                positionMs = 8_500L,
                durationMs = 20_000L,
                savedAtMs = now - 43_200_000L,
                nowMs = now
            )
        )
    }

    @Test
    fun positionOlderThanTwelveHoursIsDiscarded() {
        val now = 200_000_000L

        assertEquals(
            0L,
            DetailPlaybackRules.restoredPosition(
                positionMs = 8_500L,
                durationMs = 20_000L,
                savedAtMs = now - 43_200_001L,
                nowMs = now
            )
        )
    }

    @Test
    fun legacyOrInvalidTimestampDoesNotRestoreAStalePosition() {
        assertEquals(0L, DetailPlaybackRules.restoredPosition(8_500L, 20_000L, 0L, 200_000_000L))
        assertEquals(0L, DetailPlaybackRules.restoredPosition(8_500L, 20_000L, 200_000_001L, 200_000_000L))
    }

    @Test
    fun savedPositionAtTheEndIsDiscardedEvenWhenRecent() {
        assertEquals(
            0L,
            DetailPlaybackRules.restoredPosition(
                positionMs = 19_300L,
                durationMs = 20_000L,
                savedAtMs = 199_999_000L,
                nowMs = 200_000_000L
            )
        )
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
