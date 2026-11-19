package com.galeria.android

import android.animation.ValueAnimator
import android.os.Build
import android.view.View
import android.view.animation.PathInterpolator

/**
 * The app's shared motion: one strong ease-out for things entering, leaving or answering a touch,
 * one ease-in-out for things that move on screen, and short durations. When the system turns
 * animations off (accessibility or developer settings) the movement is dropped and only the fade
 * remains, so state changes are still legible.
 */
internal object Motion {
    /** Entering, leaving and press feedback. */
    val easeOut get() = PathInterpolator(0.23f, 1f, 0.32f, 1f)
    /** Moving or morphing on screen. */
    val easeInOut get() = PathInterpolator(0.77f, 0f, 0.175f, 1f)

    const val PRESS_MS = 160L
    const val POPOVER_MS = 180L
    const val MODAL_MS = 220L
    const val EXIT_MS = 140L

    const val PRESS_SCALE = 0.96f
    const val POPOVER_SCALE = 0.95f
    const val MODAL_SCALE = 0.96f

    /** False when the user turned animations off; callers then keep only the fade. */
    fun movementEnabled(): Boolean =
        Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

    /**
     * Brings [view] in from slightly smaller, anchored at ([pivotX], [pivotY]) in its own
     * coordinates — the trigger for popovers, the center for modals.
     */
    fun enter(
        view: View,
        fromScale: Float,
        durationMs: Long,
        pivotX: Float? = null,
        pivotY: Float? = null,
        endAction: (() -> Unit)? = null
    ) {
        view.animate().cancel()
        if (pivotX != null) view.pivotX = pivotX
        if (pivotY != null) view.pivotY = pivotY
        val scale = if (movementEnabled()) fromScale else 1f
        view.alpha = 0f
        view.scaleX = scale
        view.scaleY = scale
        view.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(durationMs)
            .setInterpolator(easeOut)
            .withEndAction { endAction?.invoke() }
            .start()
    }

    /** A small control confirming a toggle: it settles from a slightly smaller size, no overshoot. */
    fun settle(view: View) {
        view.animate().cancel()
        if (!movementEnabled()) {
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        view.scaleX = 0.86f
        view.scaleY = 0.86f
        view.animate().scaleX(1f).scaleY(1f)
            .setDuration(PRESS_MS)
            .setInterpolator(easeOut)
            .start()
    }
}
