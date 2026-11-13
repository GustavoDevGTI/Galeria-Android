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
    fun releaseReturnsToTheSpeedUsedBeforeTheHold() {
        assertEquals(2f, HoldSpeedRules.BOOST_SPEED, 0f)
        assertEquals(1f, HoldSpeedRules.speedAfterRelease(1f), 0f)
        assertEquals(1.5f, HoldSpeedRules.speedAfterRelease(1.5f), 0f)
    }
}
