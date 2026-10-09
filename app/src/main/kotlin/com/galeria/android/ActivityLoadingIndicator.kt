package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout

/** Immediate feedback and one in-flight request per user-triggered operation. Main thread only. */
internal class ActivityLoadingIndicator(private val activity: Activity) {
    private var dialog: AlertDialog? = null
    val busy: Boolean get() = dialog != null

    fun begin(message: String): Boolean {
        if (busy || activity.isFinishing || activity.isDestroyed) return false
        val body = FrameLayout(activity).apply {
            tag = "activity_loading_indicator"
            setPadding(Ui.dp(activity, 24), Ui.dp(activity, 24), Ui.dp(activity, 24), Ui.dp(activity, 24))
            background = Ui.rounded(Ui.menuSurface(activity), 20, activity)
            addView(LoadingIndicatorView(activity, Ui.menuText(activity), message),
                FrameLayout.LayoutParams(Ui.dp(activity, 32), Ui.dp(activity, 32), Gravity.CENTER))
        }
        dialog = AlertDialog.Builder(activity).setView(body).setCancelable(false).create().also {
            // A modal loading surface blocks the originating actions without a
            // second confirmation and cannot enqueue extra work behind it.
            fun position() {
                it.window?.apply {
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    attributes = attributes.apply {
                        gravity = Gravity.CENTER
                        width = Ui.dp(activity, 80)
                        height = Ui.dp(activity, 80)
                        dimAmount = 0.32f
                        windowAnimations = 0
                    }
                }
            }
            it.setOnShowListener { position() }
            it.show()
            position()
        }
        return true
    }

    fun finish() {
        dialog?.dismiss()
        dialog = null
    }
}
