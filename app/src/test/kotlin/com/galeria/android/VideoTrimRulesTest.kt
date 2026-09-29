package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoTrimRulesTest {
    @Test fun rangeClampsToDurationAndKeepsMinimumLength() {
        assertEquals(0L..12_000L, VideoTrimRules.range(12_000L, 0, 1000))
        assertEquals(11_500L..12_000L, VideoTrimRules.range(12_000L, 1000, 1000))
        assertEquals(200L..450L, VideoTrimRules.range(1_000L, 200, 300))
    }

    @Test fun outputNameKeepsOriginalNameAndUsesMp4() {
        assertEquals("filme-cortado-42.mp4", VideoTrimRules.outputName("filme.mkv", 42L))
        assertEquals("filme-cortado-42.mp4", VideoTrimRules.outputName("../filme.mp4", 42L))
    }
}
