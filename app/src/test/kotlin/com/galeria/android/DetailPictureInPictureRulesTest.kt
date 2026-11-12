package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailPictureInPictureRulesTest {

    @Test
    fun onlyAPlayingVideoTurnsIntoTheFloatingThumbnail() {
        assertTrue(DetailPictureInPictureRules.shouldEnter(true, isVideo = true, playing = true, alreadyInPictureInPicture = false, finishing = false))
        assertFalse("Foto fecha normalmente", DetailPictureInPictureRules.shouldEnter(true, false, true, false, false))
        assertFalse("Vídeo pausado fecha normalmente", DetailPictureInPictureRules.shouldEnter(true, true, false, false, false))
        assertFalse("Aparelho sem suporte fecha normalmente", DetailPictureInPictureRules.shouldEnter(false, true, true, false, false))
        assertFalse("Já em miniatura", DetailPictureInPictureRules.shouldEnter(true, true, true, true, false))
        assertFalse("Tela já fechando", DetailPictureInPictureRules.shouldEnter(true, true, true, false, true))
    }

    @Test
    fun thumbnailFollowsTheVideoShapeWithinAndroidLimits() {
        assertEquals(1920 to 1080, DetailPictureInPictureRules.aspectRatio(1920, 1080))
        assertEquals(1080 to 1920, DetailPictureInPictureRules.aspectRatio(1080, 1920))
        assertEquals("Muito largo é limitado", 239 to 100, DetailPictureInPictureRules.aspectRatio(4000, 1000))
        assertEquals("Muito alto é limitado", 100 to 239, DetailPictureInPictureRules.aspectRatio(1000, 4000))
        assertEquals("Tamanho desconhecido usa 16:9", 16 to 9, DetailPictureInPictureRules.aspectRatio(0, 0))
    }
}
