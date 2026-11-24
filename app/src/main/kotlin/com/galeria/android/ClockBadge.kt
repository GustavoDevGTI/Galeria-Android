package com.galeria.android

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.content.ContextCompat

/** The clock shown on a media used in a join: the time left, and a tap keeps the media. */
internal object ClockBadge {
    fun label(context: Context, expiresAt: Long, now: Long = System.currentTimeMillis()): String {
        val left = MediaClockRules.remaining(expiresAt, now)
        return context.getString(if (left.hours) R.string.clock_hours else R.string.clock_minutes, left.amount)
    }

    fun create(context: Context, textSp: Float, iconDp: Int, heightDp: Int): TextView = TextView(context).apply {
        tag = TAG
        setTextColor(Color.WHITE)
        textSize = textSp
        maxLines = 1
        gravity = Gravity.CENTER
        includeFontPadding = false
        background = Ui.rounded(0x99000000.toInt(), heightDp / 2, context)
        val icon = ContextCompat.getDrawable(context, R.drawable.ic_clock)?.mutate()?.apply {
            setBounds(0, 0, Ui.dp(context, iconDp), Ui.dp(context, iconDp))
        }
        setCompoundDrawables(icon, null, null, null)
        compoundDrawableTintList = ColorStateList.valueOf(Color.WHITE)
        compoundDrawablePadding = Ui.dp(context, 3)
        setPadding(Ui.dp(context, 5), 0, Ui.dp(context, 7), 0)
        isClickable = true
        isFocusable = true
        visibility = View.GONE
    }

    /** Shows the clock of [uri], or hides the badge when it has none. */
    fun bind(view: TextView, uri: String) {
        view.animate().cancel()
        view.alpha = 1f
        view.scaleX = 1f
        view.scaleY = 1f
        val clock = MediaClocks.of(view.context, uri)
        if (clock == null) {
            view.visibility = View.GONE
            return
        }
        val label = label(view.context, clock.expiresAt)
        view.text = label
        view.contentDescription = view.context.getString(R.string.clock_cancel, label)
        view.visibility = View.VISIBLE
    }

    /** Stops the clock of [uri] and lets the badge leave. */
    fun keep(view: TextView, uri: String) {
        val context = view.context
        MediaClocks.cancel(context, uri)
        Haptics.perform(view, Haptics.toggle(false))
        Ui.toast(context, context.getString(R.string.clock_cancelled))
        view.isClickable = false
        val scale = if (Motion.movementEnabled()) 0.9f else 1f
        view.animate().cancel()
        view.animate().alpha(0f).scaleX(scale).scaleY(scale)
            .setDuration(Motion.EXIT_MS)
            .setInterpolator(Motion.easeOut)
            .withEndAction {
                view.visibility = View.GONE
                view.alpha = 1f
                view.scaleX = 1f
                view.scaleY = 1f
                view.isClickable = true
            }
            .start()
    }

    const val TAG = "media_clock_badge"
}
