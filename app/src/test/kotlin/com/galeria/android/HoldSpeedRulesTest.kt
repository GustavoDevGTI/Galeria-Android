package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldSpeedRulesTest {

    @Test
    fun onlyAPlayingVerticalVideoOutsideCinemaIsBoosted() {
        assertTrue(HoldSpeedRules.canBoost(true, portrait = true, cinemaMode = false, playing = true, swipingToAnotherMedia = false, inPictureInPicture = false))
        assertFalse("Foto não acelera", HoldSpeedRules.canBoost(false, true, false, true, false, false))
        assertFalse("Paisagem mantém os gestos atuais", HoldSpeedRules.canBoost(true, false, false, true, false, false))
        assertFalse("Modo cinema tem gestos próprios", HoldSpeedRules.canBoost(true, true, true, true, false, false))
        assertFalse("Vídeo pausado não acelera", HoldSpeedRules.canBoost(true, true, false, false, false, false))
        assertFalse("Arrasto para outra mídia tem prioridade", HoldSpeedRules.canBoost(true, true, false, true, true, false))
        assertFalse("Miniatura não acelera", HoldSpeedRules.canBoost(true, true, false, true, false, true))
    }

    @Test
    fun fingerThatMovedBeforeTheHoldIsASwipe() {
        assertFalse(HoldSpeedRules.movedBeforeHold(100f, 100f, 104f, 103f, 16))
        assertTrue(HoldSpeedRules.movedBeforeHold(100f, 100f, 100f, 140f, 16))
    }

    @Test
    fun justHoldingIsTemporaryAndPullingDownKeepsTheBoost() {
        assertEquals(2f, HoldSpeedRules.BOOST_SPEED, 0f)
        assertEquals("Só segurar volta à velocidade anterior", 1f, HoldSpeedRules.speedAfterRelease(1f, locked = false), 0f)
        assertEquals(1.5f, HoldSpeedRules.speedAfterRelease(1.5f, locked = false), 0f)
        assertEquals("Segurar e puxar mantém 2x ao soltar", 2f, HoldSpeedRules.speedAfterRelease(1f, locked = true), 0f)
    }

    @Test
    fun onlyAPullDownFarEnoughLocksTheBoost() {
        assertTrue(HoldSpeedRules.locksBoost(downY = 500f, y = 640f, lockDistancePx = 130f))
        assertFalse("Puxar pouco não fixa", HoldSpeedRules.locksBoost(500f, 560f, 130f))
        assertFalse("Puxar para cima não fixa", HoldSpeedRules.locksBoost(500f, 300f, 130f))
    }
}
