package com.galeria.android

import org.junit.Assert.*
import org.junit.Test

class ViewerGestureControllersTest {
    private val slop = 10f

    private fun ImageGestureArbiter.claimText(isCurrentImage: Boolean, isFinishing: Boolean): Boolean =
        claimText { isCurrentImage && !isFinishing }

    @Test fun cancelledOrPagingTextCandidateDoesNotInspectTheMediaQueue() {
        val image = ImageGestureArbiter()
        image.start(100f, 100f)
        image.cancel()
        assertFalse(image.claimText { fail("Cancelled candidate must not inspect media"); true })
        image.start(100f, 100f)
        image.claimPaging()
        assertFalse(image.claimText { fail("Paging candidate must not inspect media"); true })
        image.start(100f, 100f)
        var queried = false
        assertTrue(image.claimText { queried = true; true })
        assertTrue(queried)
    }

    @Test fun swipeDeadZoneKeepsTapAndExactBoundaryStartsNavigation() {
        val swipe = ViewerSwipeGestureController()
        swipe.start(100f, 100f)
        assertNull(swipe.resolveIntent(109f, 100f, slop))
        assertTrue(swipe.isTap(109f, 100f, slop))
        assertEquals(SwipeIntent(SwipeAxis.HORIZONTAL, -1), swipe.resolveIntent(110f, 100f, slop))
        assertFalse(swipe.isTap(110f, 100f, slop))
    }

    @Test fun bothAxesAndDirectionsKeepExistingTranslation() {
        val cases = listOf(
            Triple(80f, 100f, SwipeIntent(SwipeAxis.HORIZONTAL, 1)),
            Triple(120f, 100f, SwipeIntent(SwipeAxis.HORIZONTAL, -1)),
            Triple(100f, 80f, SwipeIntent(SwipeAxis.VERTICAL, 1)),
            Triple(100f, 120f, SwipeIntent(SwipeAxis.VERTICAL, -1))
        )
        for ((x, y, expected) in cases) {
            val swipe = ViewerSwipeGestureController()
            swipe.start(100f, 100f)
            assertEquals(expected, swipe.resolveIntent(x, y, slop))
            val translation = swipe.translate(x, y, 200f)
            assertEquals(20f, swipe.distance, 0f)
            assertEquals(if (expected.direction > 0) -20f else 20f, translation.current, 0f)
            assertEquals(if (expected.direction > 0) 180f else -180f, translation.incoming, 0f)
        }
    }

    @Test fun diagonalTieIsHorizontalAndIntentDoesNotChangeMidDrag() {
        val swipe = ViewerSwipeGestureController()
        swipe.start(100f, 100f)
        val original = swipe.resolveIntent(80f, 80f, slop)
        assertEquals(SwipeIntent(SwipeAxis.HORIZONTAL, 1), original)
        assertEquals(original, swipe.resolveIntent(120f, 0f, slop))
        assertEquals(ViewerSwipeGestureController.Translation(-0f, 200f), swipe.translate(120f, 0f, 200f))
    }

    @Test fun distanceClampsToViewportAndKeepsCommitThreshold() {
        val swipe = ViewerSwipeGestureController()
        swipe.start(100f, 100f)
        swipe.resolveIntent(80f, 100f, slop)
        swipe.translate(86.6f, 100f, 200f)
        assertFalse(swipe.shouldCommit(slop))
        swipe.translate(86.5f, 100f, 200f)
        assertTrue(swipe.shouldCommit(slop))
        assertEquals(ViewerSwipeGestureController.Translation(-200f, 0f), swipe.translate(-500f, 100f, 200f))
    }

    @Test fun cancellationClearsDragBeforeNextSequence() {
        val swipe = ViewerSwipeGestureController()
        swipe.start(100f, 100f)
        swipe.resolveIntent(100f, 80f, slop)
        swipe.translate(100f, 80f, 200f)
        swipe.resetDrag()
        assertEquals(0, swipe.direction)
        assertEquals(0f, swipe.distance, 0f)
        swipe.start(200f, 200f)
        assertEquals(SwipeIntent(SwipeAxis.HORIZONTAL, -1), swipe.resolveIntent(220f, 200f, slop))
    }

    @Test fun translatingWithoutIntentFailsRatherThanInventingDirection() {
        assertThrows(IllegalStateException::class.java) {
            ViewerSwipeGestureController().translate(100f, 100f, 200f)
        }
    }

    @Test fun imageTapRemainsWithZoomLibraryAndAccessibilityAdapter() {
        val image = ImageGestureArbiter()
        image.start(100f, 100f)
        assertEquals(ImageGestureArbiter.MoveRoute.GALLERY, image.move(109f, 100f, 1, true, slop))
        assertTrue(image.tapCandidate)
        assertEquals(ImageGestureArbiter.Release.TAP, image.release())
        assertTrue(image.tapCandidate)
        image.finishRelease(ImageGestureArbiter.Release.TAP)
        assertFalse(image.tapCandidate)
    }

    @Test fun imageMovementCancelsTextCandidateButPreservesExistingStrictBoundary() {
        val image = ImageGestureArbiter()
        image.start(100f, 100f)
        image.move(110f, 100f, 1, true, slop)
        assertTrue(image.tapCandidate)
        image.move(110.1f, 100f, 1, true, slop)
        assertFalse(image.tapCandidate)
        assertFalse(image.claimText(true, false))
    }

    @Test fun imagePagingCancelsZoomStreamOnlyOnceAndPreventsTextClaim() {
        val image = ImageGestureArbiter()
        image.start(100f, 100f)
        assertTrue(image.claimPaging())
        assertFalse(image.claimPaging())
        assertFalse(image.claimText(true, false))
        assertEquals(ImageGestureArbiter.Release.GALLERY, image.release())
        assertTrue(image.paging)
        image.finishRelease(ImageGestureArbiter.Release.GALLERY)
        assertFalse(image.paging)
    }

    @Test fun secondPointerReturnsStreamToZoomAndInvalidatesTextAndTap() {
        val image = ImageGestureArbiter()
        image.start(100f, 100f)
        image.claimPaging()
        image.pointerDown()
        assertFalse(image.paging)
        assertFalse(image.tapCandidate)
        assertEquals(ImageGestureArbiter.MoveRoute.IMAGE_ZOOM, image.move(100f, 100f, 2, true, slop))
        assertFalse(image.claimText(true, false))
        assertEquals(ImageGestureArbiter.Release.IMAGE, image.release())
    }

    @Test fun zoomedImageOwnsDragInsteadOfGallery() {
        val image = ImageGestureArbiter()
        image.start(100f, 100f)
        image.claimPaging()
        assertEquals(ImageGestureArbiter.MoveRoute.IMAGE_ZOOM, image.move(140f, 100f, 1, false, slop))
        assertFalse(image.paging)
        assertEquals(ImageGestureArbiter.Release.IMAGE, image.release())
    }

    @Test fun textClaimRequiresCurrentLiveImageAndKeepsPriorityUntilAdapterCancelsZoom() {
        val image = ImageGestureArbiter()
        image.start(100f, 100f)
        assertFalse(image.claimText(false, false))
        assertFalse(image.claimText(true, true))
        assertTrue(image.claimText(true, false))
        assertFalse(image.tapCandidate)
        assertEquals(ImageGestureArbiter.Release.TEXT, image.release())
        assertTrue(image.textLongPressed)
        image.finishRelease(ImageGestureArbiter.Release.TEXT)
        assertFalse(image.textLongPressed)
    }

    @Test fun cancellationReportsOnlyGalleryOwnershipAndNewDownResetsText() {
        val image = ImageGestureArbiter()
        image.start(100f, 100f)
        assertFalse(image.cancel())
        assertEquals(ImageGestureArbiter.Release.IMAGE, image.release())
        image.start(100f, 100f)
        image.claimPaging()
        assertTrue(image.cancel())
        assertFalse(image.cancel())
        image.start(100f, 100f)
        image.claimText(true, false)
        image.cancel()
        assertTrue(image.textLongPressed)
        image.start(100f, 100f)
        assertFalse(image.textLongPressed)
        assertTrue(image.tapCandidate)
    }

    @Test fun oldImageStateCannotAffectNewImageSequence() {
        val old = ImageGestureArbiter()
        val current = ImageGestureArbiter()
        old.start(100f, 100f)
        current.start(200f, 200f)
        old.cancel()
        assertTrue(current.claimText(true, false))
        assertEquals(ImageGestureArbiter.Release.TEXT, current.release())
    }
}
