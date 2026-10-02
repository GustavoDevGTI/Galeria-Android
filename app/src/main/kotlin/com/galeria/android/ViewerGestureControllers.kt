package com.galeria.android

import kotlin.math.abs

/** Main-thread gesture state only; queue, pages, animations and players remain
 * owned by the viewer. Coordinates are raw screen coordinates, as before. */
internal class ViewerSwipeGestureController {
    private var downX = 0f
    private var downY = 0f
    var axis = SwipeAxis.HORIZONTAL
        private set
    var direction = 0
        private set
    var distance = 0f
        private set

    data class Translation(val current: Float, val incoming: Float)

    fun start(rawX: Float, rawY: Float) {
        downX = rawX
        downY = rawY
        distance = 0f
    }

    fun resolveIntent(rawX: Float, rawY: Float, touchSlop: Float): SwipeIntent? {
        if (direction == 0) {
            val intent = SwipeGestureRules.intent(rawX - downX, rawY - downY, touchSlop) ?: return null
            axis = intent.axis
            direction = intent.direction
        }
        return SwipeIntent(axis, direction)
    }

    fun translate(rawX: Float, rawY: Float, extent: Float): Translation {
        check(direction != 0) { "Resolve swipe intent before translating pages" }
        val delta = if (axis == SwipeAxis.HORIZONTAL) rawX - downX else rawY - downY
        distance = (if (direction > 0) -delta else delta).coerceIn(0f, extent)
        return Translation(
            current = if (direction > 0) -distance else distance,
            incoming = if (direction > 0) extent - distance else -extent + distance
        )
    }

    fun isTap(rawX: Float, rawY: Float, touchSlop: Float): Boolean =
        SwipeGestureRules.isTap(rawX - downX, rawY - downY, touchSlop)

    fun shouldCommit(touchSlop: Float): Boolean = SwipeGestureRules.shouldCommit(distance, touchSlop)

    fun resetDrag() {
        direction = 0
        distance = 0f
    }
}

/** One instance per image view: obsolete image callbacks cannot mutate the
 * active image's arbitration. Android callback scheduling and zoom stay outside. */
internal class ImageGestureArbiter {
    enum class MoveRoute { IMAGE_ZOOM, GALLERY }
    enum class Release { TEXT, GALLERY, TAP, IMAGE }

    var paging = false
        private set
    var tapCandidate = false
        private set
    var textLongPressed = false
        private set
    private var downX = 0f
    private var downY = 0f

    fun start(x: Float, y: Float) {
        textLongPressed = false
        paging = false
        tapCandidate = true
        downX = x
        downY = y
    }

    fun pointerDown() {
        paging = false
        tapCandidate = false
    }

    fun move(x: Float, y: Float, pointers: Int, atBaseScale: Boolean, touchSlop: Float): MoveRoute {
        if (abs(x - downX) > touchSlop || abs(y - downY) > touchSlop) tapCandidate = false
        if (pointers > 1 || !atBaseScale) {
            paging = false
            return MoveRoute.IMAGE_ZOOM
        }
        return MoveRoute.GALLERY
    }

    /** Returns true only for the handoff that must cancel the zoom view stream. */
    fun claimPaging(): Boolean {
        if (paging) return false
        paging = true
        return true
    }

    fun claimText(isCurrentLiveImage: () -> Boolean): Boolean {
        // Preserve short-circuiting: do not inspect a possibly replaced queue
        // for an already cancelled/zoom/paging sequence.
        if (!tapCandidate || paging || !isCurrentLiveImage()) return false
        textLongPressed = true
        tapCandidate = false
        return true
    }

    fun release(): Release = when {
        textLongPressed -> Release.TEXT
        paging -> Release.GALLERY
        tapCandidate -> Release.TAP
        else -> Release.IMAGE
    }

    /** Clear only after the adapter has cancelled zoom or committed its page;
     * callbacks during those effects must still observe the original owner. */
    fun finishRelease(release: Release) {
        when (release) {
            Release.TEXT -> textLongPressed = false
            Release.GALLERY -> paging = false
            Release.TAP, Release.IMAGE -> tapCandidate = false
        }
    }

    fun cancel(): Boolean {
        val consumed = paging
        paging = false
        tapCandidate = false
        return consumed
    }
}
