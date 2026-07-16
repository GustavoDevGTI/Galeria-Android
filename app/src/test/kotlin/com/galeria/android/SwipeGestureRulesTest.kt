package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeGestureRulesTest {
    private val slop = 10f

    @Test
    fun directTapsStayInsideTheDeadZone() {
        assertTrue(SwipeGestureRules.isTap(3f, -4f, slop))
        assertNull(SwipeGestureRules.intent(3f, -4f, slop))
    }

    @Test
    fun horizontalAndVerticalDirectionsFollowTheViewerContract() {
        assertEquals(SwipeIntent(SwipeAxis.HORIZONTAL, 1), SwipeGestureRules.intent(-11f, 2f, slop))
        assertEquals(SwipeIntent(SwipeAxis.HORIZONTAL, -1), SwipeGestureRules.intent(11f, 2f, slop))
        assertEquals(SwipeIntent(SwipeAxis.VERTICAL, 1), SwipeGestureRules.intent(2f, -11f, slop))
        assertEquals(SwipeIntent(SwipeAxis.VERTICAL, -1), SwipeGestureRules.intent(2f, 11f, slop))
    }

    @Test
    fun commitThresholdRejectsAccidentalMovement() {
        assertFalse(SwipeGestureRules.shouldCommit(13.4f, slop))
        assertTrue(SwipeGestureRules.shouldCommit(13.5f, slop))
    }
}
