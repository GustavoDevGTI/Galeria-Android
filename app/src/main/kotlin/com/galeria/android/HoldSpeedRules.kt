package com.galeria.android

/**
 * Press and hold on a vertical video to play it faster, like short-video apps.
 * Just holding is temporary: lifting the finger brings the previous speed back.
 * Holding and pulling down past [LOCK_DISTANCE_DP] keeps 2x as the chosen speed after release.
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

    const val LOCK_DISTANCE_DP = 48

    /** Pulling the held finger down far enough turns the temporary 2x into the selected speed. */
    fun locksBoost(downY: Float, y: Float, lockDistancePx: Float): Boolean = y - downY >= lockDistancePx

    /** Speed after lifting the finger: the boost stays only when it was locked by pulling down. */
    fun speedAfterRelease(speedBeforeHold: Float, locked: Boolean): Float = if (locked) BOOST_SPEED else speedBeforeHold
}
