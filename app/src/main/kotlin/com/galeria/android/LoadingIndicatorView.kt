package com.galeria.android

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.view.View
import android.widget.ImageView.ScaleType
import androidx.appcompat.widget.AppCompatImageView
import androidx.swiperefreshlayout.widget.CircularProgressDrawable

/** The same circular indicator used by pull-to-refresh, without visible loading copy. */
internal class LoadingIndicatorView @JvmOverloads constructor(context: Context, color: Int = Ui.text(context),
    description: String = context.getString(R.string.loading_in_progress)) : AppCompatImageView(context) {
    private lateinit var indicator: CircularProgressDrawable
    val isAnimating: Boolean get() = ::indicator.isInitialized && indicator.isRunning

    init {
        tag = "loading_indicator"
        contentDescription = description
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        scaleType = ScaleType.CENTER_INSIDE
        indicator = CircularProgressDrawable(context).apply {
            setStyle(CircularProgressDrawable.DEFAULT)
            setColorSchemeColors(color)
        }
        setImageDrawable(indicator)
    }

    private fun updateAnimation() {
        // ImageView may dispatch visibility callbacks before our init block.
        if (!::indicator.isInitialized) return
        val animate = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        if (isAttachedToWindow && isShown && windowVisibility == VISIBLE && animate) {
            if (!indicator.isRunning) indicator.start()
        } else {
            indicator.stop()
            // Keep an identifiable refresh symbol when system animations are off.
            indicator.setStartEndTrim(0f, 0.75f)
            indicator.setArrowEnabled(true)
        }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); updateAnimation() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateAnimation()
    }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }
    override fun onDetachedFromWindow() {
        indicator.stop()
        super.onDetachedFromWindow()
    }
}
