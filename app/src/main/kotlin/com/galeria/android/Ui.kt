package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.ScrollView
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
        fun menuSurface(context: Context): Int {
            val seed = themeSeed(context)
            return if (darkMode(context)) blend(seed, Color.BLACK, 0.74f) else blend(seed, Color.BLACK, 0.58f)
        }

        @JvmStatic
        fun menuText(context: Context): Int =
            if (darkMode(context)) TEXT else blend(Color.WHITE, themeSeed(context), 0.10f)

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
        fun showChoiceDialog(
            context: Context,
            title: String,
            labels: Array<String>,
            checkedIndex: Int,
            message: String? = null,
            neutralText: String? = null,
            onNeutral: (() -> Unit)? = null,
            onConfirm: (Int) -> Unit
        ): AlertDialog {
            var selected = checkedIndex.coerceIn(0, labels.lastIndex)
            lateinit var dialog: AlertDialog
            lateinit var content: LinearLayout
            fun refreshRows() {
                for (i in 0 until content.childCount) {
                    val row = content.getChildAt(i) as LinearLayout
                    val radio = row.findViewWithTag<RadioButton>("radio")
                    radio.isChecked = i == selected
                    row.alpha = if (radio.isChecked) 1f else 0.72f
                }
            }
            val body = themedDialogBody(context, title, message)
            content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.forEachIndexed { index, label ->
                val row = themedChoiceRow(context, label, index, true) {
                    selected = index
                    refreshRows()
                }
                content.addView(row)
            }
            body.addView(content)
            body.addView(themedDialogButtons(context, neutralText, onNeutral, { dialog.dismiss() }) {
                onConfirm(selected)
                dialog.dismiss()
            })
            dialog = AlertDialog.Builder(context).setView(body).create()
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                refreshRows()
            }
            dialog.show()
            return dialog
        }

        @JvmStatic
        fun showMultiChoiceDialog(
            context: Context,
            title: String,
            labels: Array<String>,
            checked: BooleanArray,
            message: String? = null,
            onConfirm: (BooleanArray) -> Unit
        ): AlertDialog {
            val selected = checked.copyOf()
            lateinit var dialog: AlertDialog
            val body = themedDialogBody(context, title, message)
            val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            labels.forEachIndexed { index, label ->
                val row = themedChoiceRow(context, label, index, false) {
                    selected[index] = !selected[index]
                    val check = it.findViewWithTag<CheckBox>("check")
                    check.isChecked = selected[index]
                    it.alpha = if (selected[index]) 1f else 0.72f
                }
                val check = row.findViewWithTag<CheckBox>("check")
                check.isChecked = selected[index]
                row.alpha = if (selected[index]) 1f else 0.72f
                content.addView(row)
            }
            body.addView(content)
            body.addView(themedDialogButtons(context, null, null, { dialog.dismiss() }) {
                onConfirm(selected)
                dialog.dismiss()
            })
            dialog = AlertDialog.Builder(context).setView(body).create()
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            }
            dialog.show()
            return dialog
        }

        @JvmStatic
        fun showTextInputDialog(
            context: Context,
            title: String,
            hint: String,
            message: String? = null,
            positiveText: String = "OK",
            initialValue: String = "",
            onConfirm: (String) -> Unit
        ): AlertDialog {
            lateinit var dialog: AlertDialog
            val body = themedDialogBody(context, title, message)
            val input = EditText(context).apply {
                setSingleLine(true)
                this.hint = hint
                textSize = 16f
                setTextColor(text(context))
                setHintTextColor(muted(context))
                backgroundTintList = ColorStateList.valueOf(muted(context))
                setPadding(0, dp(context, 8), 0, dp(context, 4))
                setText(initialValue)
                setSelection(initialValue.length)
            }
            body.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 58)))
            body.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    setPadding(0, dp(context, 8), 0, 0)
                    addView(themedDialogButton(context, "Cancelar") { dialog.dismiss() })
                    addView(themedDialogButton(context, positiveText, true) {
                        onConfirm(input.text.toString())
                        dialog.dismiss()
                    })
                }
            )
            dialog = AlertDialog.Builder(context).setView(body).create()
            dialog.setOnShowListener {
                dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                input.requestFocus()
            }
            dialog.show()
            return dialog
        }

        @JvmStatic
        fun setPadding(view: View, left: Int, top: Int, right: Int, bottom: Int) {
            val context = view.context
            view.setPadding(dp(context, left), dp(context, top), dp(context, right), dp(context, bottom))
        }

        @JvmStatic
        fun showPopupOptions(
            anchor: View,
            items: List<String>,
            widthDp: Int = 248,
            onSelect: (String) -> Unit
        ): PopupWindow {
            val context = anchor.context
            val scroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                background = rounded(menuSurface(context), 10, context)
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            for (item in items) {
                val row = TextView(context).apply {
                    text = item
                    setTextColor(menuText(context))
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(dp(context, 18), dp(context, 16), dp(context, 18), dp(context, 16))
                    setOnClickListener {
                        popupRef[0]?.dismiss()
                        anchor.post { onSelect(item) }
                    }
                }
                content.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            scroll.addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            val width = dp(context, widthDp)
            val popup = PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
                isOutsideTouchable = true
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                elevation = dp(context, 10).toFloat()
            }
            popupRef[0] = popup
            popup.showAsDropDown(anchor, anchor.width - width, dp(context, 6))
            return popup
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

        private fun themedDialogBody(context: Context, title: String, message: String?): LinearLayout {
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = rounded(surface(context), 4, context)
                setPadding(dp(context, 24), dp(context, 20), dp(context, 24), dp(context, 8))
            }
            body.addView(
                TextView(context).apply {
                    text = title
                    textSize = 20f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(text(context))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            if (!message.isNullOrBlank()) {
                body.addView(
                    TextView(context).apply {
                        text = message
                        textSize = 14f
                        setTextColor(text(context))
                        alpha = 0.78f
                        setPadding(0, dp(context, 6), 0, dp(context, 10))
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            }
            return body
        }

        private fun themedChoiceRow(
            context: Context,
            label: String,
            index: Int,
            radio: Boolean,
            onClick: (LinearLayout) -> Unit
        ): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(context, 48)
                setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 4))
                setBackgroundColor(if (index % 2 == 0) surface(context) else search(context))
                if (radio) {
                    addView(
                        RadioButton(context).apply {
                            tag = "radio"
                            buttonTintList = ColorStateList.valueOf(accent(context))
                            isClickable = false
                            isFocusable = false
                        }
                    )
                } else {
                    addView(
                        CheckBox(context).apply {
                            tag = "check"
                            buttonTintList = ColorStateList.valueOf(accent(context))
                            isClickable = false
                            isFocusable = false
                        }
                    )
                }
                addView(
                    TextView(context).apply {
                        text = label
                        textSize = 16f
                        setTextColor(text(context))
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener { onClick(this) }
            }

        private fun themedDialogButtons(
            context: Context,
            neutralText: String?,
            onNeutral: (() -> Unit)?,
            onCancel: () -> Unit,
            onOk: () -> Unit
        ): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setPadding(0, dp(context, 8), 0, 0)
                if (neutralText != null && onNeutral != null) {
                    addView(themedDialogButton(context, neutralText) { onNeutral() })
                }
                addView(themedDialogButton(context, "Cancelar") { onCancel() })
                addView(themedDialogButton(context, "OK", true) { onOk() })
            }

        private fun themedDialogButton(context: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(text(context))
                if (primary) setTypeface(Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
                setOnClickListener { onClick() }
            }

        private val popupRef = arrayOfNulls<PopupWindow>(1)
    }
}
