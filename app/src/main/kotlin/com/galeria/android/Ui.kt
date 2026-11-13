package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.load
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Precision
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
        internal const val THEME_COLOR_PREF = "theme_color"
        internal const val DEFAULT_THEME_SEED = 0xFF121212.toInt()
        private const val MAX_THEME_SATURATION = ThemeToneRules.MAX_THEME_SATURATION
        private var previewThemeSeed: Int? = null

        @JvmStatic
        fun darkMode(context: Context): Boolean = ThemeToneRules.darkMode(themeSeed(context))

        /**
         * Returns the 4 derived screen tones for the active theme:
         * [0] Base background (`bg`)
         * [1] Primary surface (`surface`)
         * [2] Elevated card / secondary control surface (`elevatedSurface`)
         * [3] Highlight / active selection surface (`highlightSurface`)
         */
        @JvmStatic
        fun themeTones(context: Context): IntArray = ThemeToneRules.screenTones(themeSeed(context))

        /**
         * Returns the 4 derived submenu/dialog tones for the active theme, each with a single semantic role:
         * [0] Submenu/dialog container backdrop (`menuSurface`)
         * [1] Standard unselected option row surface (`menuOptionSurface`)
         * [2] Distinct interactive control / secondary action surface (`menuElevatedSurface`)
         * [3] Active/selected option / primary confirmation surface (`menuHighlightSurface`)
         */
        @JvmStatic
        fun menuTones(context: Context): IntArray = ThemeToneRules.menuTones(themeSeed(context))

        @JvmStatic
        fun bg(context: Context): Int = themeTones(context)[0]

        @JvmStatic
        fun surface(context: Context): Int = themeTones(context)[1]

        @JvmStatic
        fun elevatedSurface(context: Context): Int = themeTones(context)[2]

        @JvmStatic
        fun highlightSurface(context: Context): Int = themeTones(context)[3]

        @JvmStatic
        fun search(context: Context): Int = ThemeToneRules.search(themeSeed(context))

        @JvmStatic
        fun menuSurface(context: Context): Int = menuTones(context)[0]

        @JvmStatic
        fun menuOptionSurface(context: Context): Int = menuTones(context)[1]

        @JvmStatic
        fun menuElevatedSurface(context: Context): Int = menuTones(context)[2]

        @JvmStatic
        fun menuHighlightSurface(context: Context): Int = menuTones(context)[3]

        @JvmStatic
        fun menuText(context: Context): Int = ThemeToneRules.menuText(themeSeed(context))

        @JvmStatic
        fun text(context: Context): Int = ThemeToneRules.text(themeSeed(context))

        @JvmStatic
        fun selectionActionIcon(context: Context): Int =
            if (luminance(text(context)) >= 0.5) Color.WHITE else Color.BLACK

        @JvmStatic
        fun muted(context: Context): Int = ThemeToneRules.muted(themeSeed(context))

        @JvmStatic
        fun accent(context: Context): Int = ThemeToneRules.accent(themeSeed(context))

        @JvmStatic
        fun border(context: Context): Int = ThemeToneRules.border(themeSeed(context))

        @JvmStatic
        fun menuBorder(context: Context): Int = ThemeToneRules.menuBorder(themeSeed(context))

        @JvmStatic
        fun themeSeed(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return previewThemeSeed
                ?: normalizeThemeSeed(prefs.getInt(THEME_COLOR_PREF, DEFAULT_THEME_SEED))
        }

        @JvmStatic
        fun normalizeThemeSeed(color: Int): Int = ThemeToneRules.normalizeSeed(color)

        @JvmStatic
        fun setThemePreview(color: Int) {
            previewThemeSeed = normalizeThemeSeed(color)
        }

        @JvmStatic
        fun clearThemePreview() {
            previewThemeSeed = null
        }

        @JvmStatic
        fun applySystemBars(activity: Activity) {
            val background = bg(activity)
            activity.window.statusBarColor = background
            activity.window.navigationBarColor = background
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
                val darkIcons = luminance(background) >= 0.56
                isAppearanceLightStatusBars = darkIcons
                isAppearanceLightNavigationBars = darkIcons
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                activity.window.isStatusBarContrastEnforced = false
                activity.window.isNavigationBarContrastEnforced = false
            }
        }

        @JvmStatic
        fun applySystemBarPadding(
            view: View,
            leftDp: Int,
            topDp: Int,
            rightDp: Int,
            bottomDp: Int
        ) {
            val context = view.context
            ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
                val topInset = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                target.setPadding(
                    dp(context, leftDp),
                    topInset + dp(context, topDp),
                    dp(context, rightDp),
                    dp(context, bottomDp)
                )
                insets
            }
            ViewCompat.requestApplyInsets(view)
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
        fun rounded(
            color: Int,
            radiusDp: Int,
            context: Context,
            strokeColor: Int,
            strokeWidthDp: Int = 1
        ): GradientDrawable =
            GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(context, radiusDp).toFloat()
                setStroke(max(1, dp(context, strokeWidthDp)), strokeColor)
            }

        /**
         * The selection counter shares one look on every screen (albums and media inside an album):
         * same size, weight and color, and the same "selected/total" format.
         */
        @JvmStatic
        fun styleSelectionCounter(input: TextView, selecting: Boolean, idleTextSizeSp: Float) {
            val context = input.context
            input.textSize = if (selecting) SELECTION_COUNTER_SP else idleTextSizeSp
            input.setTypeface(Typeface.DEFAULT, if (selecting) Typeface.BOLD else Typeface.NORMAL)
            input.setHintTextColor(if (selecting) text(context) else muted(context))
        }

        const val SELECTION_COUNTER_SP = 20f

        @JvmStatic
        fun styleSelectionToggle(view: TextView, checked: Boolean) {
            val context = view.context
            view.text = if (checked) "✓" else ""
            view.setTextColor(if (checked) bg(context) else text(context))
            view.background = GradientDrawable().apply {
                setColor(if (checked) accent(context) else Color.TRANSPARENT)
                setStroke(dp(context, 2), if (checked) accent(context) else muted(context))
                cornerRadius = dp(context, 6).toFloat()
            }
            view.contentDescription = if (checked) "Desmarcar todos" else "Selecionar todos"
        }

        const val ACTION_ICON_SIZE_DP = 22
        const val ACTION_TOUCH_HEIGHT_DP = 56

        /**
         * Reaction to pressing and holding a media item or album: vibration (only when touch
         * feedback is enabled in the phone settings) plus a short shrink of the item.
         * The view's own automatic feedback is turned off so the phone vibrates only once,
         * even when the list rebinds the item during the long press.
         */
        @JvmStatic
        fun longPressFeedback(view: View, shrink: Boolean = true) {
            Haptics.perform(view, Haptics.Effect.LONG_PRESS)
            view.isHapticFeedbackEnabled = false
            if (shrink) {
                view.animate().scaleX(LONG_PRESS_SCALE).scaleY(LONG_PRESS_SCALE).alpha(LONG_PRESS_ALPHA)
                    .setDuration(90).start()
            }
        }

        const val LONG_PRESS_SCALE = 0.94f
        const val LONG_PRESS_ALPHA = 0.78f

        fun actionFeedback(context: Context, color: Int, selected: Boolean = false): RippleDrawable =
            RippleDrawable(
                ColorStateList.valueOf((color and 0x00FFFFFF) or 0x14000000),
                if (selected) rounded((color and 0x00FFFFFF) or 0x16000000, 18, context) else null,
                rounded(Color.WHITE, 18, context)
            )

        fun actionIconButton(context: Context, icon: Int, color: Int): ImageButton =
            ClickFeedbackImageButton(context).apply {
                setImageResource(icon)
                imageTintList = ColorStateList.valueOf(color)
                scaleType = ImageView.ScaleType.CENTER
                setPadding(0, 0, 0, 0)
                minimumWidth = dp(context, 48)
                minimumHeight = dp(context, ACTION_TOUCH_HEIGHT_DP)
                background = actionFeedback(context, color)
            }

        @JvmStatic
        fun selectionAction(context: Context, icon: Int, label: String, listener: () -> Unit): LinearLayout =
            ClickFeedbackActionLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                minimumHeight = dp(context, 64)
                contentDescription = label
                isClickable = true
                isFocusable = true
                background = actionFeedback(context, selectionActionIcon(context))
                setPadding(dp(context, 2), dp(context, 8), dp(context, 2), dp(context, 8))
                addView(
                    ImageView(context).apply {
                        tag = SELECTION_ACTION_ICON_TAG
                        setImageResource(icon)
                        imageTintList = ColorStateList.valueOf(selectionActionIcon(context))
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(dp(context, ACTION_ICON_SIZE_DP), dp(context, ACTION_ICON_SIZE_DP))
                )
                addView(
                    TextView(context).apply {
                        tag = SELECTION_ACTION_LABEL_TAG
                        text = label
                        textSize = 11f
                        setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                        setTextColor(text(context))
                        alpha = 0.86f
                        gravity = Gravity.CENTER
                        maxLines = 1
                        ellipsize = android.text.TextUtils.TruncateAt.END
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(context, 5)
                    }
                )
                setOnClickListener { listener() }
            }

        @JvmStatic
        fun selectionActionDock(context: Context): LinearLayout =
            LinearLayout(context).apply {
                tag = SELECTION_ACTION_DOCK_TAG
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(context, 64)
                background = ColorDrawable(surface(context))
                elevation = 0f
                clipToOutline = false
                setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
            }

        @JvmStatic
        fun addSelectionActionToDock(dock: LinearLayout, action: View) {
            dock.addView(action, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        @JvmStatic
        fun restyleSelectionAction(view: View) {
            val context = view.context
            view.background = actionFeedback(context, selectionActionIcon(context))
            view.findViewWithTag<ImageView>(SELECTION_ACTION_ICON_TAG)?.imageTintList =
                ColorStateList.valueOf(selectionActionIcon(context))
            view.findViewWithTag<TextView>(SELECTION_ACTION_LABEL_TAG)?.setTextColor(text(context))
        }

        @JvmStatic
        fun restyleSelectionActionDock(dock: LinearLayout) {
            val context = dock.context
            dock.background = ColorDrawable(surface(context))
            for (index in 0 until dock.childCount) {
                val child = dock.getChildAt(index)
                restyleSelectionAction(child)
            }
        }

        @JvmStatic
        fun applySidePanelStyle(dialog: AlertDialog, widthFraction: Float = SIDE_PANEL_WIDTH_FRACTION, fullHeight: Boolean = false) {
            val window = dialog.window ?: return
            val context = dialog.context
            val metrics = context.resources.displayMetrics
            val width = min(dp(context, SIDE_PANEL_MAX_WIDTH_DP), (metrics.widthPixels * widthFraction).roundToInt())
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply {
                gravity = Gravity.END or Gravity.TOP
                this.width = width
                height = if (fullHeight) {
                    min(
                        metrics.heightPixels - dp(context, SIDE_PANEL_TOP_MARGIN_DP + SIDE_PANEL_BOTTOM_MARGIN_DP),
                        (metrics.heightPixels * SIDE_PANEL_MAX_HEIGHT_FRACTION).roundToInt()
                    )
                } else {
                    WindowManager.LayoutParams.WRAP_CONTENT
                }
                x = dp(context, SIDE_PANEL_END_MARGIN_DP)
                y = dp(context, SIDE_PANEL_TOP_MARGIN_DP)
                dimAmount = 0.32f
                windowAnimations = 0
            }
        }

        @JvmStatic
        fun showSidePanel(
            dialog: AlertDialog,
            widthFraction: Float = SIDE_PANEL_WIDTH_FRACTION,
            fullHeight: Boolean = false,
            onShown: (() -> Unit)? = null
        ): AlertDialog {
            val initialDecor = dialog.window?.decorView
            initialDecor?.alpha = 0f
            applySidePanelStyle(dialog, widthFraction, fullHeight)
            dialog.setOnShowListener {
                applySidePanelStyle(dialog, widthFraction, fullHeight)
                val decor = dialog.window?.decorView ?: return@setOnShowListener
                alignPrimaryDialogActions(decor, Gravity.START)
                decor.alpha = 0f
                decor.postDelayed({
                    if (!dialog.isShowing) return@postDelayed
                    applySidePanelStyle(dialog, widthFraction, fullHeight)
                    decor.animate()
                        .alpha(1f)
                        .setDuration(SIDE_PANEL_FADE_MS)
                        .withEndAction { onShown?.invoke() }
                        .start()
                }, SIDE_PANEL_LAYOUT_DELAY_MS)
            }
            dialog.show()
            return dialog
        }

        @JvmStatic
        fun showCenteredPanel(dialog: AlertDialog, fullHeight: Boolean = false): AlertDialog {
            fun position() {
                val window = dialog.window ?: return
                val metrics = dialog.context.resources.displayMetrics
                window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                window.attributes = window.attributes.apply {
                    gravity = Gravity.CENTER
                    width = min(dp(dialog.context, 440), (metrics.widthPixels * 0.90f).roundToInt())
                    height = if (fullHeight) (metrics.heightPixels * 0.78f).roundToInt()
                        else WindowManager.LayoutParams.WRAP_CONTENT
                    x = 0
                    y = 0
                    dimAmount = 0.32f
                    windowAnimations = 0
                }
                alignPrimaryDialogActions(window.decorView, Gravity.END)
            }
            position()
            dialog.setOnShowListener { position() }
            dialog.show()
            return dialog
        }

        @JvmStatic
        fun toast(context: Context, message: String) {
            if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            } else {
                android.os.Handler(android.os.Looper.getMainLooper()).post { toast(context, message) }
            }
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
            require(labels.isNotEmpty())
            var selected = checkedIndex.coerceIn(0, labels.lastIndex)
            lateinit var dialog: AlertDialog
            lateinit var content: LinearLayout
            fun refreshRows() {
                for (i in 0 until content.childCount) {
                    val row = content.getChildAt(i) as LinearLayout
                    val radio = row.findViewWithTag<RadioButton>("radio")
                    radio.isChecked = i == selected
                    styleDialogChoiceRow(row, radio.isChecked)
                }
            }
            val body = themedDialogBody(context, title, message)
            content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 10), 0, dp(context, 10), dp(context, 8))
            }
            labels.forEachIndexed { index, label ->
                val row = themedChoiceRow(context, label, true) {
                    selected = index
                    refreshRows()
                    dialog.dismiss()
                    onConfirm(selected)
                }
                row.findViewWithTag<RadioButton>("radio").visibility = View.GONE
                content.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 0
                })
            }
            body.addView(content)
            if (neutralText != null && onNeutral != null) {
                body.addView(themedDialogButton(context, neutralText) {
                    dialog.dismiss()
                    onNeutral()
                }, menuActionParams())
            }
            dialog = AlertDialog.Builder(context).setView(body).create()
            refreshRows()
            showSidePanel(dialog) {
                refreshRows()
            }
            return dialog
        }

        @JvmStatic
        fun showActionDialog(
            context: Context,
            title: String,
            labels: List<String>,
            onSelect: (Int) -> Unit
        ): AlertDialog {
            require(labels.isNotEmpty())
            lateinit var dialog: AlertDialog
            val body = themedDialogBody(context, title, null)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 10), 0, dp(context, 10), dp(context, 10))
            }
            labels.forEachIndexed { index, label ->
                val row = themedChoiceRow(context, label, true) {
                    dialog.dismiss()
                    onSelect(index)
                }
                row.findViewWithTag<RadioButton>("radio").visibility = View.GONE
                styleDialogChoiceRow(row, false)
                content.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 0
                })
            }
            body.addView(content)
            dialog = AlertDialog.Builder(context).setView(body).create()
            return showCenteredPanel(dialog)
        }

        @JvmStatic
        fun showSortChoiceDialog(
            context: Context,
            title: String,
            labels: Array<String>,
            modes: Array<String>,
            checkedIndex: Int,
            descending: Boolean,
            message: String? = null,
            onConfirm: (Int, Boolean) -> Unit
        ): AlertDialog {
            require(labels.isNotEmpty() && labels.size == modes.size)
            val selected = checkedIndex.coerceIn(0, labels.lastIndex)
            lateinit var dialog: AlertDialog
            val body = themedDialogBody(context, title, message)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 10), 0, dp(context, 10), dp(context, 8))
            }
            labels.forEachIndexed { index, label ->
                val active = index == selected
                val arrow = TextView(context).apply {
                    tag = "sort_direction_arrow"
                    text = if (descending) "↓" else "↑"
                    textSize = 21f
                    setTextColor(menuText(context))
                    visibility = if (active && SortDirectionRules.supportsDirection(modes[index])) View.VISIBLE else View.GONE
                }
                content.addView(LinearLayout(context).apply {
                    tag = "sort_option_${modes[index]}"
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(context, 50)
                    setPadding(dp(context, 16), dp(context, 4), dp(context, 16), dp(context, 4))
                    addView(TextView(context).apply {
                        text = label
                        textSize = 16f
                        setTextColor(menuText(context))
                    }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    addView(arrow)
                    isClickable = true
                    isFocusable = true
                    contentDescription = if (active && SortDirectionRules.supportsDirection(modes[index])) {
                        "$label, ${context.getString(if (descending) R.string.action_descending else R.string.action_ascending)}"
                    } else label
                    styleDialogChoiceRow(this, active)
                    setOnClickListener {
                        Haptics.performFromOverlay(this, Haptics.Effect.TAP)
                        val nextDescending = SortDirectionRules.whenModeSelected(
                            modes[selected], modes[index], descending
                        )
                        dialog.dismiss()
                        onConfirm(index, nextDescending)
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 0
                })
            }
            body.addView(content)
            dialog = AlertDialog.Builder(context).setView(body).create()
            showSidePanel(dialog)
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
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 10), 0, dp(context, 10), dp(context, 8))
            }
            labels.forEachIndexed { index, label ->
                val row = themedChoiceRow(context, label, false) {
                    selected[index] = !selected[index]
                    val check = it.findViewWithTag<CheckBox>("check")
                    check.isChecked = selected[index]
                    styleDialogChoiceRow(it, selected[index])
                    dialog.dismiss()
                    onConfirm(selected.copyOf())
                }
                val check = row.findViewWithTag<CheckBox>("check")
                check.isChecked = selected[index]
                styleDialogChoiceRow(row, selected[index])
                content.addView(row, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 0
                })
            }
            body.addView(content)
            dialog = AlertDialog.Builder(context).setView(body).create()
            showSidePanel(dialog)
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
            val body = themedDialogBody(context, title, null)
            if (!message.isNullOrBlank()) {
                body.addView(TextView(context).apply {
                    text = message
                    textSize = 14f
                    setTextColor(menuText(context))
                    background = rounded(menuOptionSurface(context), 10, context)
                    setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(context, 14)
                    marginEnd = dp(context, 14)
                    bottomMargin = dp(context, 8)
                })
            }
            val input = EditText(context).apply {
                setSingleLine(true)
                this.hint = hint
                textSize = 16f
                setTextColor(menuText(context))
                setHintTextColor(menuText(context))
                background = rounded(menuElevatedSurface(context), 10, context, menuBorder(context), 1)
                setPadding(dp(context, 14), dp(context, 10), dp(context, 14), dp(context, 10))
                setText(initialValue)
                setSelection(initialValue.length)
            }
            body.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 52)).apply {
                marginStart = dp(context, 14)
                marginEnd = dp(context, 14)
                bottomMargin = dp(context, 4)
            })
            body.addView(
                themedActionPair(
                    context,
                    context.getString(R.string.action_cancel),
                    positiveText,
                    onFirst = { dialog.dismiss() },
                    onSecond = {
                        onConfirm(input.text.toString())
                        dialog.dismiss()
                    }
                )
            )
            dialog = AlertDialog.Builder(context).setView(body).create()
            showSidePanel(dialog) {
                input.requestFocus()
            }
            return dialog
        }

        @JvmStatic
        fun showMessageDialog(
            context: Context,
            title: String,
            message: String,
            positiveText: String = "Fechar"
        ): AlertDialog {
            lateinit var dialog: AlertDialog
            val body = themedDialogBody(context, title, message)
            body.addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(themedDialogButton(context, positiveText, true) { dialog.dismiss() }, menuActionParams())
                }
            )
            dialog = AlertDialog.Builder(context).setView(body).create()
            return showCenteredPanel(dialog)
        }

        @JvmStatic
        internal fun showMetadataDialog(
            context: Context,
            title: String,
            sections: List<DetailMetadataSection>
        ): AlertDialog {
            lateinit var dialog: AlertDialog
            val body = themedDialogBody(context, title, null)
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 12), 0, dp(context, 12), dp(context, 8))
            }
            for (section in sections) {
                val group = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    background = rounded(menuOptionSurface(context), 12, context, menuBorder(context), 1)
                    setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 8))
                }
                group.addView(TextView(context).apply {
                    text = section.title
                    textSize = 14f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(menuText(context))
                    setPadding(0, 0, 0, dp(context, 8))
                })
                for (field in section.fields) {
                    val label = TextView(context).apply {
                        text = field.label
                        textSize = 13f
                        setTypeface(Typeface.DEFAULT_BOLD)
                        setTextColor(menuText(context))
                    }
                    val value = TextView(context).apply {
                        text = field.value
                        textSize = 15f
                        setTextColor(menuText(context))
                        setTextIsSelectable(true)
                    }
                    group.addView(label)
                    group.addView(value, LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(context, 10) })
                }
                content.addView(group, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(context, 10) })
            }
            body.addView(ScrollView(context).apply {
                isFillViewport = false
                addView(content)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            body.addView(themedDialogButton(context, context.getString(R.string.action_close), true) {
                dialog.dismiss()
            }, menuActionParams())
            dialog = AlertDialog.Builder(context).setView(body).create()
            return showCenteredPanel(dialog, fullHeight = true)
        }

        @JvmStatic
        fun showConfirmationDialog(
            context: Context,
            title: String,
            message: String,
            positiveText: String,
            negativeText: String = "Cancelar",
            onNegative: (() -> Unit)? = null,
            onPositive: () -> Unit
        ): AlertDialog {
            lateinit var dialog: AlertDialog
            val body = themedDialogBody(context, title, message)
            body.addView(
                themedActionPair(
                    context,
                    negativeText,
                    positiveText,
                    onFirst = {
                        onNegative?.invoke()
                        dialog.dismiss()
                    },
                    onSecond = {
                        onPositive()
                        dialog.dismiss()
                    }
                )
            )
            dialog = AlertDialog.Builder(context).setView(body).create()
            return showCenteredPanel(dialog)
        }

        @JvmStatic
        fun showActionChoiceDialog(
            context: Context,
            title: String,
            message: String,
            firstLabel: String,
            secondLabel: String,
            onFirst: () -> Unit,
            onSecond: () -> Unit
        ): AlertDialog {
            lateinit var dialog: AlertDialog
            val body = themedDialogBody(context, title, message)
            val choices = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 16))
            }
            fun choice(label: String, primary: Boolean, action: () -> Unit) =
                themedDialogButton(context, label, primary) {
                    dialog.dismiss()
                    action()
                }.apply {
                    tag = null // Both access choices have identical alignment and prominence.
                    gravity = Gravity.CENTER
                    textSize = 14f
                    minHeight = dp(context, 64)
                    val tone = if (primary) menuHighlightSurface(context) else menuElevatedSurface(context)
                    background = RippleDrawable(
                        ColorStateList.valueOf((menuText(context) and 0x00FFFFFF) or 0x22000000),
                        rounded(tone, 12, context, menuBorder(context), 1),
                        rounded(Color.WHITE, 12, context)
                    )
                    setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8))
                }
            choices.addView(choice(firstLabel, true, onFirst), LinearLayout.LayoutParams(0, dp(context, 64), 1f).apply {
                marginEnd = dp(context, 4)
            })
            choices.addView(choice(secondLabel, false, onSecond), LinearLayout.LayoutParams(0, dp(context, 64), 1f).apply {
                marginStart = dp(context, 4)
            })
            body.addView(choices)
            dialog = AlertDialog.Builder(context).setView(body).create()
            return showCenteredPanel(dialog)
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
            selectedItems: Set<String> = emptySet(),
            onSelect: (String) -> Unit
        ): PopupWindow {
            val context = anchor.context
            val scroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                background = rounded(menuSurface(context), SIDE_PANEL_RADIUS_DP, context, menuBorder(context), 1)
                clipToOutline = true
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 6), dp(context, 6), dp(context, 6), dp(context, 6))
            }
            val pendingSelection = arrayOfNulls<String>(1)
            val localPopupRef = arrayOfNulls<PopupWindow>(1)
            items.forEachIndexed { index, item ->
                val isSelected = selectedItems.contains(item)
                val row = TextView(context).apply {
                    text = item
                    setTextColor(menuText(context))
                    textSize = 15f
                    gravity = Gravity.CENTER_VERTICAL or Gravity.START
                    setPadding(dp(context, 14), dp(context, 12), dp(context, 14), dp(context, 12))
                    background = flatRowBackground(context, isSelected)
                    if (isSelected) {
                        setTypeface(Typeface.DEFAULT_BOLD)
                    }
                    setOnClickListener {
                        Haptics.performFromOverlay(this, Haptics.Effect.TAP)
                        pendingSelection[0] = item
                        localPopupRef[0]?.dismiss()
                    }
                }
                content.addView(
                    row,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply {
                        bottomMargin = 0
                    }
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
                setOnDismissListener {
                    pendingSelection[0]?.let { selected ->
                        pendingSelection[0] = null
                        anchor.postDelayed({ onSelect(selected) }, POPUP_ACTION_DELAY_MS)
                    }
                }
            }
            localPopupRef[0] = popup
            popup.showAsDropDown(anchor, anchor.width - width, dp(context, 6))
            return popup
        }

        @JvmStatic
        fun markPrimaryDialogAction(view: TextView): TextView = view.apply {
            tag = DIALOG_PRIMARY_ACTION_TAG
        }

        /** Shared album presentation for moving files and configuring the organizer. */
        fun albumTargetRow(context: Context, album: AlbumItem): LinearLayout {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(context, 60)
                setPadding(dp(context, 12), dp(context, 6), dp(context, 14), dp(context, 6))
                contentDescription = "Álbum ${album.name}"
            }
            row.addView(ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(Color.BLACK, 6, context)
                clipToOutline = true
                album.cover?.let { media ->
                    load(media.uri) {
                        ImageRotation.configureRequest(context, media, this)
                        size(dp(context, 48), dp(context, 48))
                        precision(Precision.INEXACT)
                        memoryCacheKey("album-target:${MediaContentRevision.key(context, media.uri)}:48")
                        diskCacheKey("album-target:${MediaContentRevision.key(context, media.uri)}")
                        allowHardware(true)
                        crossfade(160)
                    }
                }
            }, LinearLayout.LayoutParams(dp(context, 46), dp(context, 46)))
            row.addView(TextView(context).apply {
                text = album.name
                textSize = 16f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(menuText(context))
                setPadding(dp(context, 12), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return row
        }

        @JvmStatic
        fun showAlbumTargets(
            anchor: View,
            title: String,
            albums: List<AlbumItem>,
            onSelect: (AlbumItem) -> Unit
        ): PopupWindow {
            val context = anchor.context
            val scroll = ScrollView(context).apply {
                isVerticalScrollBarEnabled = true
                background = rounded(menuSurface(context), SIDE_PANEL_RADIUS_DP, context, menuBorder(context), 1)
                clipToOutline = true
            }
            val content = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 6))
                addView(
                    TextView(context).apply {
                        text = title
                        textSize = 13f
                        setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                        setTextColor(menuText(context))
                        alpha = 0.78f
                        setPadding(dp(context, 10), dp(context, 10), dp(context, 10), dp(context, 8))
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            }
            val pendingSelection = arrayOfNulls<AlbumItem>(1)
            val localPopupRef = arrayOfNulls<PopupWindow>(1)
            albums.forEachIndexed { index, album ->
                val row = albumTargetRow(context, album)
                row.background = RippleDrawable(
                    ColorStateList.valueOf((menuText(context) and 0x00FFFFFF) or 0x22000000),
                    rounded(menuOptionSurface(context), 10, context),
                    rounded(Color.WHITE, 10, context)
                )
                row.setOnClickListener {
                    pendingSelection[0] = album
                    localPopupRef[0]?.dismiss()
                }
                content.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 60)).apply {
                    bottomMargin = if (index < albums.lastIndex) dp(context, 3) else 0
                })
            }
            scroll.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val width = min(dp(context, 320), context.resources.displayMetrics.widthPixels - dp(context, 24))
            val location = IntArray(2)
            anchor.getLocationOnScreen(location)
            val top = location[1] + anchor.height + dp(context, 6)
            val availableHeight = max(dp(context, 180), context.resources.displayMetrics.heightPixels - top - dp(context, 16))
            val desiredHeight = dp(context, 56 + albums.size * 63)
            val popup = PopupWindow(scroll, width, min(availableHeight, desiredHeight), true).apply {
                isOutsideTouchable = true
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                elevation = dp(context, 12).toFloat()
                setOnDismissListener {
                    pendingSelection[0]?.let { selected ->
                        pendingSelection[0] = null
                        anchor.postDelayed({ onSelect(selected) }, POPUP_ACTION_DELAY_MS)
                    }
                }
            }
            localPopupRef[0] = popup
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
                background = rounded(menuSurface(context), SIDE_PANEL_RADIUS_DP, context, menuBorder(context), 1)
                clipToOutline = true
            }
            body.addView(
                TextView(context).apply {
                    text = title
                    textSize = 18f
                    setTypeface(Typeface.DEFAULT_BOLD)
                    setTextColor(menuText(context))
                    setPadding(dp(context, 18), dp(context, 18), dp(context, 18), dp(context, 10))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            if (!message.isNullOrBlank()) {
                body.addView(
                    TextView(context).apply {
                        text = message
                        textSize = 14f
                        setTextColor(menuText(context))
                        alpha = 0.78f
                        setPadding(dp(context, 18), 0, dp(context, 18), dp(context, 12))
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            }
            return body
        }

        private fun themedChoiceRow(
            context: Context,
            label: String,
            radio: Boolean,
            onClick: (LinearLayout) -> Unit
        ): LinearLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(context, 50)
                setPadding(dp(context, 12), dp(context, 4), dp(context, 14), dp(context, 4))
                background = actionFeedback(context, menuText(context))
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
                        setTextColor(menuText(context))
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    Haptics.performFromOverlay(this, Haptics.Effect.TAP)
                    onClick(this)
                }
            }

        private fun styleDialogChoiceRow(row: LinearLayout, selected: Boolean) {
            val context = row.context
            row.isSelected = selected
            row.background = flatRowBackground(context, selected)
        }

        /** Linha de lista sem cartão: só a seleção recebe um fundo suave; as demais mostram apenas o toque. */
        private fun flatRowBackground(context: Context, selected: Boolean): RippleDrawable =
            RippleDrawable(
                ColorStateList.valueOf((menuText(context) and 0x00FFFFFF) or 0x22000000),
                if (selected) rounded(menuHighlightSurface(context), 10, context) else null,
                rounded(Color.WHITE, 10, context)
            )

        private fun dialogActionColor(context: Context, primary: Boolean): Int {
            if (!primary) return menuText(context)
            val accent = accent(context)
            return if (ThemeToneRules.contrastRatio(accent, menuSurface(context)) >= 4.5) accent else menuText(context)
        }

        private fun themedDialogButton(context: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
            TextView(context).apply {
                text = label
                textSize = 15f
                setTextColor(dialogActionColor(context, primary))
                if (primary) {
                    setTypeface(Typeface.DEFAULT_BOLD)
                    tag = DIALOG_PRIMARY_ACTION_TAG
                }
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                minimumHeight = dp(context, 48)
                isClickable = true
                isFocusable = true
                background = flatRowBackground(context, false)
                setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10))
                setOnClickListener {
                    Haptics.performFromOverlay(this, if (primary) Haptics.Effect.CONFIRM else Haptics.Effect.TAP)
                    onClick()
                }
            }

        private fun themedActionPair(
            context: Context,
            firstLabel: String,
            secondLabel: String,
            onFirst: () -> Unit,
            onSecond: () -> Unit
        ): LinearLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 8))
            fun addAction(label: String, primary: Boolean, marginStart: Int, marginEnd: Int, action: () -> Unit) {
                val button = themedDialogButton(context, label, primary, action).apply {
                    tag = null
                    gravity = Gravity.CENTER
                }
                addView(button, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    this.marginStart = dp(context, marginStart)
                    this.marginEnd = dp(context, marginEnd)
                })
            }
            addAction(firstLabel, false, 0, 4, onFirst)
            addAction(secondLabel, true, 4, 0, onSecond)
        }

        private fun alignPrimaryDialogActions(view: View, horizontalGravity: Int) {
            if (view.tag == DIALOG_PRIMARY_ACTION_TAG && view is TextView) {
                view.gravity = Gravity.CENTER_VERTICAL or horizontalGravity
            }
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) {
                    alignPrimaryDialogActions(view.getChildAt(index), horizontalGravity)
                }
            }
        }

        private fun menuActionParams(): LinearLayout.LayoutParams =
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = 20
                marginEnd = 20
                bottomMargin = 16
            }

        private const val POPUP_ACTION_DELAY_MS = 48L
        private const val SIDE_PANEL_LAYOUT_DELAY_MS = 16L
        private const val SIDE_PANEL_FADE_MS = 90L
        private const val SELECTION_ACTION_ICON_TAG = "selection_action_icon"
        private const val SELECTION_ACTION_LABEL_TAG = "selection_action_label"
        private const val SELECTION_ACTION_DOCK_TAG = "selection_action_dock"
        private const val DIALOG_PRIMARY_ACTION_TAG = "dialog_primary_action"
        private const val SIDE_PANEL_MAX_WIDTH_DP = 280
        private const val SIDE_PANEL_RADIUS_DP = 16
        private const val SIDE_PANEL_END_MARGIN_DP = 8
        private const val SIDE_PANEL_TOP_MARGIN_DP = 72
        private const val SIDE_PANEL_BOTTOM_MARGIN_DP = 28
        private const val SIDE_PANEL_WIDTH_FRACTION = 0.60f
        private const val SIDE_PANEL_MAX_HEIGHT_FRACTION = 0.82f
    }
}
