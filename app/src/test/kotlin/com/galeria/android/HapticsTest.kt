package com.galeria.android

import android.view.HapticFeedbackConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class HapticsTest {

    @Test
    fun eachMeaningUsesItsOwnVibrationOnCurrentAndroid() {
        val constants = Haptics.Effect.entries.map { Haptics.constantFor(it, 36) }
        assertEquals("Cada efeito deve ter uma vibração própria", constants.size, constants.toSet().size)
        assertEquals(HapticFeedbackConstants.CONFIRM, Haptics.constantFor(Haptics.Effect.CONFIRM, 36))
        assertEquals(HapticFeedbackConstants.REJECT, Haptics.constantFor(Haptics.Effect.REJECT, 36))
        assertEquals(HapticFeedbackConstants.TOGGLE_ON, Haptics.constantFor(Haptics.Effect.TOGGLE_ON, 36))
        assertEquals(HapticFeedbackConstants.TOGGLE_OFF, Haptics.constantFor(Haptics.Effect.TOGGLE_OFF, 36))
    }

    @Test
    fun olderAndroidFallsBackToVibrationsThatExistThere() {
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, Haptics.constantFor(Haptics.Effect.CONFIRM, 29))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, Haptics.constantFor(Haptics.Effect.REJECT, 29))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, Haptics.constantFor(Haptics.Effect.TOGGLE_ON, 33))
        assertEquals(HapticFeedbackConstants.CLOCK_TICK, Haptics.constantFor(Haptics.Effect.THRESHOLD, 33))
        assertNotEquals(
            "Aceitar e recusar continuam diferentes no Android antigo",
            Haptics.constantFor(Haptics.Effect.CONFIRM, 23),
            Haptics.constantFor(Haptics.Effect.REJECT, 23)
        )
    }

    @Test
    fun toggleMapsStateToOnAndOff() {
        assertEquals(Haptics.Effect.TOGGLE_ON, Haptics.toggle(true))
        assertEquals(Haptics.Effect.TOGGLE_OFF, Haptics.toggle(false))
    }
}
