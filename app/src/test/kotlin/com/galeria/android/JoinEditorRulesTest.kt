package com.galeria.android

import org.junit.Assert.assertEquals
import org.junit.Test

class JoinEditorRulesTest {
    @Test fun draggedSquareLandsInTheSlotUnderItsCentre() {
        // 16 px padding, 56 px squares, 8 px gaps: slots start at 16, 80, 144.
        assertEquals(80f, JoinStripRules.slotX(1, 16f, 56f, 8f))
        assertEquals(0, JoinStripRules.slotAt(20f, 3, 16f, 56f, 8f))
        assertEquals(1, JoinStripRules.slotAt(110f, 3, 16f, 56f, 8f))
        assertEquals(2, JoinStripRules.slotAt(900f, 3, 16f, 56f, 8f))
        assertEquals(0, JoinStripRules.slotAt(-50f, 3, 16f, 56f, 8f))
    }

    @Test fun otherSquaresStepAsideForTheDraggedOne() {
        // Square 0 held over slot 2: squares 1 and 2 move one place left.
        assertEquals(listOf(2, 0, 1, 3), (0..3).map { JoinStripRules.displayedSlot(it, 0, 2) })
        // Square 3 held over slot 1: squares 1 and 2 move one place right.
        assertEquals(listOf(0, 2, 3, 1), (0..3).map { JoinStripRules.displayedSlot(it, 3, 1) })
    }

    @Test fun timelineKeepsThePlayheadInTheMiddle() {
        val pxPerMs = 0.1f
        assertEquals(500f, JoinTimelineRules.xAt(2_000L, 2_000L, 1000, pxPerMs))
        assertEquals(600f, JoinTimelineRules.xAt(3_000L, 2_000L, 1000, pxPerMs))
        assertEquals(1_000L, JoinTimelineRules.timeAt(400f, 2_000L, 1000, pxPerMs))
        assertEquals(1_250L, JoinTimelineRules.frameTime(1_499L))
    }
}
