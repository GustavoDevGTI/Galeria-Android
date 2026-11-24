package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageResizeRulesTest {
    @Test fun onlySizesSmallerThanThePhotoAreOffered() {
        assertEquals(listOf(2160, 1440, 1080, 720, 540, 480, 360), ImageResizeRules.options(4000, 3000))
        assertEquals(listOf(720, 540, 480, 360), ImageResizeRules.options(1920, 1080))
        assertEquals(emptyList<Int>(), ImageResizeRules.options(160, 120))
    }

    @Test fun theShortSideIsTheChosenOneAndTheShapeStays() {
        assertEquals(PixelSize(2880, 2160), ImageResizeRules.scaled(4000, 3000, 2160))
        assertEquals(PixelSize(1080, 1920), ImageResizeRules.scaled(3024, 5376, 1080))
        assertEquals(PixelSize(480, 360), ImageResizeRules.scaled(1600, 1200, 360))
    }

    @Test fun theEstimateFollowsTheOriginalBytesPerPixel() {
        // 12 MP in 4 MB: a quarter of the pixels is about 1 MB.
        assertEquals(1_000_000L, ImageResizeRules.estimatedBytes(4_000_000L, 4000, 3000, PixelSize(2000, 1500)))
        assertNull(ImageResizeRules.estimatedBytes(0L, 4000, 3000, PixelSize(2000, 1500)))
    }

    @Test fun bigReductionsGoThroughHalvesBeforeTheExactSize() {
        assertEquals(listOf(PixelSize(2000, 1500), PixelSize(1000, 750), PixelSize(960, 720)),
            ImageResizeRules.steps(PixelSize(4000, 3000), PixelSize(960, 720)))
        assertEquals(listOf(PixelSize(2000, 1500)), ImageResizeRules.steps(PixelSize(4000, 3000), PixelSize(2000, 1500)))
        assertEquals(listOf(PixelSize(1440, 1080)), ImageResizeRules.steps(PixelSize(1920, 1440), PixelSize(1440, 1080)))
    }
}
