package com.galeria.android

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageButton
import android.widget.LinearLayout

/** A momentary click pulse, independent of selected/pressed state and HUD animations. */
internal class ActionClickFeedback(private val view: View) {
    private var animation: AnimatorSet? = null
    private var restingScaleX = 1f
    private var restingScaleY = 1f

    fun play() {
        if (!view.isAttachedToWindow || !view.isEnabled ||
            (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            reset()
            return
        }
        val fromScaleX = view.scaleX
        val fromScaleY = view.scaleY
        if (animation == null) {
            restingScaleX = fromScaleX
            restingScaleY = fromScaleY
        }
        val previous = animation
        animation = null
        previous?.cancel()
        val pulse = AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.SCALE_X, fromScaleX, restingScaleX * 0.94f, restingScaleX),
                ObjectAnimator.ofFloat(view, View.SCALE_Y, fromScaleY, restingScaleY * 0.94f, restingScaleY)
            )
            duration = 180L
            interpolator = AccelerateDecelerateInterpolator()
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animator: Animator) {
                    if (animation === animator) {
                        animation = null
                        view.scaleX = restingScaleX
                        view.scaleY = restingScaleY
                    }
                }
            })
        }
        animation = pulse
        pulse.start()
    }

    fun reset() {
        val previous = animation ?: return
        animation = null
        previous.cancel()
        view.scaleX = restingScaleX
        view.scaleY = restingScaleY
    }
}

// The black editors explicitly use a framework Material theme. Keep the same
// framework button there instead of imposing AppCompat styling on those screens.
@SuppressLint("AppCompatCustomView")
internal class ClickFeedbackImageButton(context: Context) : ImageButton(context) {
    private val clickFeedback = ActionClickFeedback(this)

    override fun performClick(): Boolean {
        if (hasOnClickListeners()) clickFeedback.play()
        return super.performClick()
    }

    override fun onDetachedFromWindow() {
        clickFeedback.reset()
        super.onDetachedFromWindow()
    }
}

internal class ClickFeedbackActionLayout(context: Context) : LinearLayout(context) {
    private val clickFeedback = ActionClickFeedback(this)

    override fun performClick(): Boolean {
        if (hasOnClickListeners()) clickFeedback.play()
        return super.performClick()
    }

    override fun onDetachedFromWindow() {
        clickFeedback.reset()
        super.onDetachedFromWindow()
    }
}
