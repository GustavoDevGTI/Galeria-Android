package com.galeria.android

/**
 * Press and hold on a vertical video to play it faster, like short-video apps:
 * the boost lasts only while the finger stays down, including while dragging,
 * and the previous speed comes back on release.
 */
internal object HoldSpeedRules {
    const val BOOST_SPEED = 2f

    fun canBoost(
        isVideo: Boolean,
        portrait: Boolean,
        cinemaMode: Boolean,
        playing: Boolean,
        swipingToAnotherMedia: Boolean,
        inPictureInPicture: Boolean
    ): Boolean = isVideo && portrait && !cinemaMode && playing && !swipingToAnotherMedia && !inPictureInPicture

    /** A finger that already moved is a swipe, so the pending hold is cancelled before it starts. */
    fun movedBeforeHold(downX: Float, downY: Float, x: Float, y: Float, touchSlop: Int): Boolean {
        val dx = x - downX
        val dy = y - downY
        return dx * dx + dy * dy > touchSlop.toFloat() * touchSlop
    }

    /** Speed to return to on release; an already boosted speed is kept as it was. */
    fun speedAfterRelease(speedBeforeHold: Float): Float = speedBeforeHold
}
