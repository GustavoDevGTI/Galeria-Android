package com.galeria.android

import android.view.MotionEvent
import kotlin.math.atan2

/** Pure decisions behind the optional viewer gestures, kept apart so they can be tested. */
internal object ViewerGestureRules {
    /** Edge width, as a fraction of the screen, that changes media with "Trocar mídia tocando nas laterais". */
    const val SIDE_TAP_FRACTION = 0.2f

    /** A swipe that goes down (not up, not sideways). */
    fun isPullDown(intent: SwipeIntent): Boolean = intent.axis == SwipeAxis.VERTICAL && intent.direction < 0

    /** Pulling down closes past [minDistancePx] or a quarter of the screen, whichever comes first. */
    fun closesOnRelease(distancePx: Float, screenHeightPx: Float, minDistancePx: Float): Boolean =
        distancePx >= minDistancePx || (screenHeightPx > 0f && distancePx >= screenHeightPx * 0.25f)

    /** -1 for the left edge, 1 for the right edge, 0 for the middle of the screen. */
    fun sideTapDirection(x: Float, width: Float): Int = when {
        width <= 0f -> 0
        x < width * SIDE_TAP_FRACTION -> -1
        x > width * (1f - SIDE_TAP_FRACTION) -> 1
        else -> 0
    }

    /** Cinema gestures use both halves; the brightness option alone uses only the left half. */
    fun verticalAdjustAllowed(cinemaGestures: Boolean, brightnessGesture: Boolean, x: Float, width: Float): Boolean =
        cinemaGestures || (brightnessGesture && width > 0f && x < width / 2f)

    /** Quarter turns for a two-finger twist: one step past 45 degrees, never more than one per gesture. */
    fun quarterTurns(twistDegrees: Float): Int = when {
        twistDegrees >= 45f -> 1
        twistDegrees <= -45f -> -1
        else -> 0
    }
}

/** Follows the angle between two fingers and reports a single quarter turn per gesture. */
internal class RotationGestureTracker {
    private var startAngle: Float? = null
    private var turned = false

    fun start() {
        startAngle = null
        turned = false
    }

    /** Returns +1/-1 once the twist passes 45 degrees, otherwise null. */
    fun update(event: MotionEvent): Int? {
        if (turned || event.pointerCount < 2) return null
        val angle = Math.toDegrees(
            atan2((event.getY(1) - event.getY(0)).toDouble(), (event.getX(1) - event.getX(0)).toDouble())
        ).toFloat()
        val begin = startAngle ?: run { startAngle = angle; return null }
        var twist = angle - begin
        if (twist > 180f) twist -= 360f
        if (twist < -180f) twist += 360f
        val turns = ViewerGestureRules.quarterTurns(twist)
        if (turns == 0) return null
        turned = true
        return turns
    }
}
