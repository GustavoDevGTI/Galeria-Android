package com.galeria.android

import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/** Full-screen lock shown before any other screen when the whole app is protected by password. */
class LockActivity : ComponentActivity() {
    private lateinit var password: EditText
    private lateinit var error: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Keep the protected content out of the recent-apps preview while locked.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        Ui.applySystemBars(this)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // Leaving the lock screen without the password leaves the app.
            override fun handleOnBackPressed() { finishAffinity() }
        })
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.bg(this@LockActivity))
            Ui.setPadding(this, 32, 32, 32, 32)
        }
        root.addView(ImageView(this).apply { setImageResource(R.drawable.ic_launcher) },
            LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 88)))
        root.addView(Ui.title(this, getString(R.string.security_app_locked), 20).apply {
            gravity = Gravity.CENTER
            Ui.setPadding(this, 0, 18, 0, 6)
        })
        root.addView(Ui.label(this, getString(R.string.security_app_locked_message)).apply { gravity = Gravity.CENTER })
        password = EditText(this).apply {
            tag = "lock_password"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = getString(R.string.security_password_hint)
            setTextColor(Ui.text(this@LockActivity))
            setHintTextColor(Ui.muted(this@LockActivity))
            background = Ui.rounded(Ui.elevatedSurface(this@LockActivity), 12, this@LockActivity, Ui.border(this@LockActivity), 1)
            Ui.setPadding(this, 14, 10, 14, 10)
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ -> tryUnlock(); true }
        }
        root.addView(password, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)).apply {
            topMargin = Ui.dp(this@LockActivity, 22)
        })
        error = Ui.label(this, "").apply {
            gravity = Gravity.CENTER
            setTextColor(android.graphics.Color.rgb(238, 112, 112))
        }
        root.addView(error)
        root.addView(Ui.title(this, getString(R.string.security_unlock), 16).apply {
            tag = "lock_unlock"
            gravity = Gravity.CENTER
            background = Ui.actionFeedback(this@LockActivity, Ui.text(this@LockActivity))
            setOnClickListener { tryUnlock() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 52)).apply {
            topMargin = Ui.dp(this@LockActivity, 10)
        })
        setContentView(root)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        password.requestFocus()
    }

    private fun tryUnlock() {
        if (SecurityStore(this).verifyAppPassword(password.text.toString())) {
            Haptics.perform(this, Haptics.Effect.CONFIRM)
            SecuritySession.appUnlocked = true
            finish()
            overridePendingTransition(0, 0)
        } else {
            Haptics.perform(this, Haptics.Effect.REJECT)
            password.setText("")
            error.text = getString(R.string.security_wrong_password)
        }
    }
}
