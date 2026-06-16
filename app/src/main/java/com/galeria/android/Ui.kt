package com.galeria.android

import android.app.Activity
import android.os.Build
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Ui private constructor() {
    companion object {
        const val PREFS: String = "gallery_albums"
        @JvmField val BG: Int = Color.rgb(16, 18, 20)
        @JvmField val SURFACE: Int = Color.rgb(26, 26, 26)
        @JvmField val SEARCH: Int = Color.rgb(18, 18, 18)
        @JvmField val TEXT: Int = Color.rgb(245, 247, 250)
        @JvmField val MUTED: Int = Color.rgb(170, 178, 189)
        @JvmField val ACCENT: Int = Color.rgb(245, 247, 250)

        @JvmStatic
        fun darkMode(context: Context): Boolean = luminance(themeSeed(context)) < 0.56

        @JvmStatic
        fun bg(context: Context): Int {
            val seed = themeSeed(context)
            return if (darkMode(context)) blend(seed, Color.BLACK, 0.72f) else blend(seed, Color.WHITE, 0.82f)
        }

        @JvmStatic
        fun surface(context: Context): Int {
            val seed = themeSeed(context)
            return if (darkMode(context)) blend(seed, Color.BLACK, 0.52f) else blend(seed, Color.WHITE, 0.66f)
        }

        @JvmStatic
        fun search(context: Context): Int {
            val seed = themeSeed(context)
            return if (darkMode(context)) blend(seed, Color.BLACK, 0.62f) else blend(seed, Color.WHITE, 0.74f)
        }

        @JvmStatic
        fun text(context: Context): Int = if (darkMode(context)) TEXT else Color.rgb(18, 18, 18)

        @JvmStatic
        fun muted(context: Context): Int {
            val base = text(context)
            return if (darkMode(context)) blend(base, bg(context), 0.36f) else blend(base, bg(context), 0.46f)
        }

        @JvmStatic
        fun accent(context: Context): Int {
            val seed = themeSeed(context)
            if (darkMode(context) && luminance(seed) < 0.35) {
                return blend(seed, Color.WHITE, 0.45f)
            }
            if (!darkMode(context) && luminance(seed) > 0.72) {
                return blend(seed, Color.BLACK, 0.25f)
            }
            return seed
        }

        @JvmStatic
        fun themeSeed(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getInt("theme_color", Color.rgb(18, 18, 18))
        }

        @JvmStatic
        fun blend(first: Int, second: Int, secondAmount: Float): Int {
            val amount = max(0f, min(1f, secondAmount))
            val inverse = 1f - amount
            return Color.rgb(
                (Color.red(first) * inverse + Color.red(second) * amount).roundToInt(),
                (Color.green(first) * inverse + Color.green(second) * amount).roundToInt(),
                (Color.blue(first) * inverse + Color.blue(second) * amount).roundToInt()
            )
        }

        @JvmStatic
        fun luminance(color: Int): Double =
            (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0

        @JvmStatic
        fun dp(context: Context, value: Int): Int =
            (value * context.resources.displayMetrics.density + 0.5f).toInt()

        @JvmStatic
        fun title(context: Context, text: String, sp: Int): TextView =
            TextView(context).apply {
                this.text = text
                setTextColor(text(context))
                textSize = sp.toFloat()
                setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                isSingleLine = true
            }

        @JvmStatic
        fun label(context: Context, text: String): TextView =
            TextView(context).apply {
                this.text = text
                setTextColor(muted(context))
                textSize = 14f
                gravity = Gravity.CENTER
            }

        @JvmStatic
        fun button(context: Context, text: String): Button =
            Button(context).apply {
                this.text = text
                setTextColor(text(context))
                isAllCaps = false
                setBackgroundColor(surface(context))
                minHeight = dp(context, 42)
            }

        @JvmStatic
        fun rounded(color: Int, radiusDp: Int, context: Context): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(context, radiusDp).toFloat()
            }

        @JvmStatic
        fun toast(context: Context, message: String) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }

        @JvmStatic
        fun setPadding(view: View, left: Int, top: Int, right: Int, bottom: Int) {
            val context = view.context
            view.setPadding(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom))
        }

        @JvmStatic
        fun applyOpenTransition(activity: Activity) {
            applyActivityTransition(activity, true)
        }

        @JvmStatic
        fun applyCloseTransition(activity: Activity) {
            applyActivityTransition(activity, false)
        }

        private fun applyActivityTransition(activity: Activity, opening: Boolean) {
            val enterAnim = android.R.anim.fade_in
            val exitAnim = android.R.anim.fade_out
            if (Build.VERSION.SDK_INT >= 34) {
                val overrideType = if (opening) Activity.OVERRIDE_TRANSITION_OPEN else Activity.OVERRIDE_TRANSITION_CLOSE
                activity.overrideActivityTransition(overrideType, enterAnim, exitAnim)
            } else {
                @Suppress("DEPRECATION")
                activity.overridePendingTransition(enterAnim, exitAnim)
            }
        }
    }
}
