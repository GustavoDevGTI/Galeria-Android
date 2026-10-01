package com.galeria.android

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Editor controls deliberately do not inherit the gallery's user-selected accent. */
internal object EditorUi {
    fun button(context: Context, icon: Int, label: String, action: () -> Unit): ImageButton = ClickFeedbackImageButton(context).apply {
        setImageResource(icon)
        setColorFilter(Color.WHITE)
        contentDescription = label
        if (Build.VERSION.SDK_INT >= 26) tooltipText = label
        val padding = Ui.dp(context, 12)
        setPadding(padding, padding, padding, padding)
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x14FFFFFF),
            ColorDrawable(Color.TRANSPARENT), Ui.rounded(Color.WHITE, 12, context)
        )
        minimumWidth = Ui.dp(context, 48)
        minimumHeight = Ui.dp(context, 48)
        setOnClickListener { action() }
    }

    fun editMenu(activity: Activity, actions: List<() -> Unit>) {
        lateinit var dialog: AlertDialog
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 20), Ui.dp(context, 16), Ui.dp(context, 20), Ui.dp(context, 16))
            background = Ui.rounded(0xFF191919.toInt(), 24, context)
            addView(TextView(context).apply { text = context.getString(R.string.action_edit); textSize = 18f; setTextColor(Color.WHITE) })
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                val icons = listOf(R.drawable.ic_crop, R.drawable.ic_rotate, R.drawable.ic_edit)
                val labels = listOf(R.string.image_edit_crop, R.string.image_edit_rotate, R.string.image_edit_custom)
                icons.forEachIndexed { index, icon ->
                    addView(button(context, icon, context.getString(labels[index])) { dialog.dismiss(); actions[index]() },
                        LinearLayout.LayoutParams(0, Ui.dp(context, 64), 1f))
                }
            })
        }
        dialog = AlertDialog.Builder(activity, android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK).setView(body).create()
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.CENTER)
            setLayout(minOf(Ui.dp(activity, 340), activity.resources.displayMetrics.widthPixels - Ui.dp(activity, 48)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    fun recognizedText(activity: Activity, text: String) {
        val result = TextView(activity).apply {
            this.text = text
            textSize = 16f
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(Ui.dp(context, 24), Ui.dp(context, 16), Ui.dp(context, 24), Ui.dp(context, 16))
        }
        AlertDialog.Builder(activity, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
            .setTitle(R.string.action_recognize_text)
            .setView(ScrollView(activity).apply { addView(result) })
            .setPositiveButton(R.string.ocr_copy_all) { _, _ ->
                (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(activity.getString(R.string.action_recognize_text), text))
                Ui.toast(activity, activity.getString(R.string.ocr_copied))
            }.setNegativeButton(R.string.action_close, null).show()
    }
}
