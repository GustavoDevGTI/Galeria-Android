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

    @Test
    fun uprightPhoneVideoKeepsAVerticalShape() {
        assertEquals("Vídeo gravado em pé vem deitado com rotação de 90°", 1080 to 1920,
            DetailPictureInPictureRules.orientedSize(1920, 1080, 1f, 90))
        assertEquals(1080 to 1920, DetailPictureInPictureRules.orientedSize(1920, 1080, 1f, 270))
        assertEquals("Sem rotação o formato é o original", 1920 to 1080,
            DetailPictureInPictureRules.orientedSize(1920, 1080, 1f, 0))
        assertEquals("Pixels não quadrados alargam a imagem", 1440 to 1080,
            DetailPictureInPictureRules.orientedSize(1080, 1080, 4f / 3f, 0))
        assertEquals(0 to 0, DetailPictureInPictureRules.orientedSize(0, 0, 1f, 0))
    }
}
