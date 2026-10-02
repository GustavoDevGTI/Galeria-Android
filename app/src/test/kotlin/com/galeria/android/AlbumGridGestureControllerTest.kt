package com.galeria.android

import org.junit.Assert.*
import org.junit.Test

class AlbumGridGestureControllerTest {
    private fun AlbumGridGestureController.route(pointerDown: Boolean = false, pointers: Int = 1,
        listMode: Boolean = false, reordering: Boolean = false) =
        route(pointerDown, pointers, listMode, reordering)

    @Test fun ordinaryTouchBelongsToScrollAndRefresh() {
        assertEquals(AlbumGridGestureController.Route.SCROLL, AlbumGridGestureController().route())
    }

    @Test fun pinchStartsOnlyWithSecondPointerInGridAndNotDuringReorder() {
        val controller = AlbumGridGestureController()
        assertEquals(AlbumGridGestureController.Route.PINCH, controller.route(pointerDown = true, pointers = 2))
        assertEquals(AlbumGridGestureController.Route.SCROLL, controller.route(pointerDown = true, pointers = 1))
        assertEquals(AlbumGridGestureController.Route.SCROLL, controller.route(pointerDown = true, pointers = 2, listMode = true))
        assertEquals(AlbumGridGestureController.Route.REORDER, controller.route(pointerDown = true, pointers = 2, reordering = true))
    }

    @Test fun longPressSelectionOwnsTheStreamInsteadOfRefreshOrReorder() {
        val controller = AlbumGridGestureController()
        controller.beginSelection(2)
        assertEquals(AlbumGridGestureController.Route.SELECTION, controller.route())
        assertEquals(AlbumGridGestureController.Route.SELECTION, controller.route(reordering = true))
        controller.endSelection()
        assertEquals(AlbumGridGestureController.Route.SCROLL, controller.route())
    }

    @Test fun selectionIncludesEveryPositionInBothDirectionsWithoutToggling() {
        val controller = AlbumGridGestureController()
        val visited = arrayListOf<Int>()
        val selected = hashSetOf<Int>()
        val select: (Int) -> Boolean = { visited.add(it); selected.add(it) }
        controller.beginSelection(2)
        assertTrue(controller.selectThrough(5, select))
        assertEquals(listOf(2, 3, 4, 5), visited)
        visited.clear()
        assertTrue(controller.selectThrough(1, select))
        assertEquals(listOf(1, 2, 3, 4, 5), visited)
        assertEquals(setOf(1, 2, 3, 4, 5), selected)
        visited.clear()
        assertFalse(controller.selectThrough(5, select))
        assertEquals(listOf(1, 2, 3, 4, 5), visited)
    }

    @Test fun emptySpaceAndRepeatedTargetDoNotAdvanceSelectionAnchor() {
        val controller = AlbumGridGestureController()
        controller.beginSelection(3)
        assertFalse(controller.selectThrough(-1) { fail("No item in empty space"); false })
        assertFalse(controller.selectThrough(3) { fail("Repeated position"); false })
        val visited = arrayListOf<Int>()
        controller.selectThrough(5) { visited.add(it) }
        assertEquals(listOf(3, 4, 5), visited)
    }

    @Test fun endedSelectionCannotChangeAdapterAndNextSelectionHasNewAnchor() {
        val controller = AlbumGridGestureController()
        controller.beginSelection(1)
        controller.endSelection()
        assertFalse(controller.selectThrough(3) { fail("Selection ended"); false })
        controller.beginSelection(5)
        val visited = arrayListOf<Int>()
        controller.selectThrough(6) { visited.add(it) }
        assertEquals(listOf(5, 6), visited)
    }

    @Test fun pinchTakesPriorityOverSelectionAndClearsItsAnchor() {
        val controller = AlbumGridGestureController()
        controller.beginSelection(2)
        assertEquals(AlbumGridGestureController.Route.PINCH, controller.route(pointerDown = true, pointers = 2))
        controller.beginPinch(100f)
        assertFalse(controller.selectionActive)
        assertEquals(AlbumGridGestureController.Route.PINCH, controller.route())
        assertFalse(controller.selectThrough(5) { fail("Pinch owns gesture"); false })
    }

    @Test fun lastFingerAfterPinchCannotFallThroughToRefresh() {
        val controller = AlbumGridGestureController()
        controller.beginPinch(100f)
        controller.pointerUp()
        assertFalse(controller.pinchActive)
        assertEquals(AlbumGridGestureController.Route.PINCH, controller.route())
        assertEquals(0, controller.movePinch(200f))
        controller.endPinch()
        assertEquals(AlbumGridGestureController.Route.SCROLL, controller.route())
    }

    @Test fun densityAccumulatesSmallMovesAndResetsOnlyAfterColumnStep() {
        val controller = AlbumGridGestureController()
        controller.beginPinch(100f)
        assertEquals(0, controller.movePinch(104f))
        assertEquals(-1, controller.movePinch(109f))
        assertEquals(0, controller.movePinch(113f))
        assertEquals(-1, controller.movePinch(119f))
    }

    @Test fun shrinkingPinchIncreasesColumnsAndKeepsOneStepPerMove() {
        val controller = AlbumGridGestureController()
        controller.beginPinch(100f)
        assertEquals(1, controller.movePinch(90f))
        assertEquals(1, controller.movePinch(45f))
    }

    @Test fun discontinuousOrNonFiniteSpansDoNotChangeColumnsAndRecover() {
        val controller = AlbumGridGestureController()
        controller.beginPinch(100f)
        assertEquals(0, controller.movePinch(500f))
        assertEquals(0, controller.movePinch(Float.NaN))
        assertEquals(0, controller.movePinch(100f))
        assertEquals(-1, controller.movePinch(109f))
        assertEquals(0, controller.movePinch(Float.POSITIVE_INFINITY))
        assertEquals(0, controller.movePinch(100f))
        assertEquals(-1, controller.movePinch(109f))
    }

    @Test fun cancellingPinchDiscardsAccumulatedScaleForNextGesture() {
        val controller = AlbumGridGestureController()
        controller.beginPinch(100f)
        controller.movePinch(104f)
        controller.endPinch()
        controller.beginPinch(100f)
        assertEquals(0, controller.movePinch(104f))
        assertEquals(-1, controller.movePinch(109f))
    }
}
