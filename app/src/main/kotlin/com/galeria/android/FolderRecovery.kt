package com.galeria.android

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Secret question of the folders protected by password: asked when a folder is protected, and
 * used by "Esqueci a senha" to open the folder and create a new password.
 */
internal object FolderRecovery {
    /** The question as shown: a ready one in the phone language, or the user's own text. */
    fun questionText(context: Context, stored: String?): String? {
        val presets = context.resources.getStringArray(R.array.security_recovery_questions)
        return RecoveryRules.presetIndex(stored)?.let(presets::getOrNull) ?: stored
    }

    /** Question and answer for the folders in [albumKeys]; [onDone] says whether they were saved. */
    fun askToCreate(context: Context, albumKeys: List<String>, title: String, onDone: (Boolean) -> Unit = {}) {
        val store = SecurityStore(context)
        val presets = context.resources.getStringArray(R.array.security_recovery_questions)
        var chosen: Int? = null
        var saved = false
        lateinit var dialog: AlertDialog
        val body = Ui.themedDialogBody(context, title, context.getString(R.string.security_recovery_create_message))
        val customQuestion = field(context, R.string.security_recovery_question_hint, secret = false).apply {
            tag = "recovery_custom_question"
            visibility = View.GONE
        }
        val picker = TextView(context).apply {
            tag = "recovery_question_picker"
            text = context.getString(R.string.security_recovery_choose)
            textSize = 15f
            setTextColor(Ui.menuText(context))
            background = Ui.rounded(Ui.menuOptionSurface(context), 10, context, Ui.menuBorder(context), 1)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val labels = presets + context.getString(R.string.security_recovery_custom)
                Ui.showChoiceDialog(context, context.getString(R.string.security_recovery_choose), labels,
                    chosen ?: -1) { which ->
                    chosen = which
                    text = labels[which]
                    customQuestion.visibility = if (which == presets.size) View.VISIBLE else View.GONE
                    if (which == presets.size) customQuestion.requestFocus()
                }
            }
        }
        body.addView(picker, params(context))
        body.addView(customQuestion, params(context))
        val answer = field(context, R.string.security_recovery_answer_hint, secret = false).apply { tag = "recovery_answer" }
        body.addView(answer, params(context))
        val error = errorText(context)
        body.addView(error)
        body.addView(Ui.themedActionPair(
            context,
            context.getString(R.string.security_recovery_later),
            context.getString(R.string.security_recovery_save),
            onFirst = { dialog.dismiss() },
            onSecond = {
                val index = chosen
                val question = when {
                    index == null -> ""
                    index < presets.size -> RecoveryRules.PRESET_PREFIX + index
                    else -> customQuestion.text.toString().trim()
                }
                if (question.isEmpty() || !RecoveryRules.isAcceptable(answer.text.toString())) {
                    error.text = context.getString(R.string.security_recovery_missing_fields)
                    error.visibility = View.VISIBLE
                    Haptics.perform(answer, Haptics.Effect.REJECT)
                } else {
                    albumKeys.forEach { store.setRecovery(it, question, answer.text.toString()) }
                    saved = true
                    Ui.toast(context, context.getString(R.string.security_recovery_saved))
                    dialog.dismiss()
                }
            }
        ))
        dialog = AlertDialog.Builder(context).setView(body).create()
        dialog.setOnDismissListener { onDone(saved) }
        Ui.showCenteredPanel(dialog)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
    }

    /** "Esqueci a senha": the right answer opens the folder and then offers a new password. */
    fun recover(context: Context, albumKey: String, albumName: String, onOpened: () -> Unit) {
        val store = SecurityStore(context)
        val question = questionText(context, store.recoveryQuestion(albumKey))
        if (!store.hasRecovery(albumKey) || question.isNullOrBlank()) {
            Ui.showMessageDialog(context, context.getString(R.string.security_forgot_password),
                context.getString(R.string.security_recovery_none))
            return
        }
        lateinit var dialog: AlertDialog
        val body = Ui.themedDialogBody(context, context.getString(R.string.security_forgot_password), question)
        val answer = field(context, R.string.security_recovery_answer_hint, secret = false).apply { tag = "recovery_answer" }
        body.addView(answer, params(context))
        val error = errorText(context)
        body.addView(error)
        fun submit() {
            if (store.verifyRecovery(albumKey, answer.text.toString())) {
                dialog.dismiss()
                SecuritySession.unlockFolder(albumKey)
                onOpened()
                offerNewPassword(context, albumKey, albumName)
            } else {
                error.text = context.getString(R.string.security_recovery_wrong)
                error.visibility = View.VISIBLE
                answer.setText("")
                Haptics.perform(answer, Haptics.Effect.REJECT)
            }
        }
        answer.setOnEditorActionListener { _, _, _ -> submit(); true }
        body.addView(Ui.themedActionPair(
            context,
            context.getString(R.string.action_cancel),
            context.getString(R.string.security_unlock),
            onFirst = { dialog.dismiss() },
            onSecond = { submit() }
        ))
        dialog = AlertDialog.Builder(context).setView(body).create()
        Ui.showCenteredPanel(dialog)
        dialog.window?.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        answer.requestFocus()
    }

    private fun offerNewPassword(context: Context, albumKey: String, albumName: String) {
        Ui.showPasswordDialog(
            context,
            context.getString(R.string.security_recovery_new_password_title, albumName),
            context.getString(R.string.security_recovery_new_password_message),
            confirm = true
        ) { password ->
            SecurityStore(context).setFolderPassword(albumKey, password)
            Ui.toast(context, context.getString(R.string.security_password_saved))
            null
        }
    }

    private fun field(context: Context, hint: Int, secret: Boolean) = EditText(context).apply {
        setSingleLine(true)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        this.hint = context.getString(hint)
        textSize = 16f
        setTextColor(Ui.menuText(context))
        setHintTextColor(Ui.muted(context))
        background = Ui.rounded(Ui.menuElevatedSurface(context), 10, context, Ui.menuBorder(context), 1)
        setPadding(Ui.dp(context, 14), Ui.dp(context, 10), Ui.dp(context, 14), Ui.dp(context, 10))
    }

    private fun errorText(context: Context) = TextView(context).apply {
        tag = "recovery_error"
        textSize = 13f
        setTextColor(Color.rgb(238, 112, 112))
        visibility = View.GONE
        setPadding(Ui.dp(context, 18), 0, Ui.dp(context, 18), Ui.dp(context, 4))
    }

    private fun params(context: Context) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        marginStart = Ui.dp(context, 14)
        marginEnd = Ui.dp(context, 14)
        bottomMargin = Ui.dp(context, 8)
    }
}
