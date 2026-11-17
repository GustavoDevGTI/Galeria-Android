package com.galeria.android

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock

/**
 * Asks for the right password before a protected action and remembers the answer for this visit.
 * When nothing is protected the action simply runs.
 */
internal object SecurityGate {

    /** Opening the hidden items: needs the app password when "Proteger visualização de ocultos" is on. */
    fun requireHiddenAccess(context: Context, onCancel: (() -> Unit)? = null, action: () -> Unit) {
        val store = SecurityStore(context)
        if (!store.lockHiddenItems || SecuritySession.hiddenUnlocked) { action(); return }
        askAppPassword(context, context.getString(R.string.security_hidden_title), onCancel) {
            SecuritySession.hiddenUnlocked = true
            action()
        }
    }

    /** Deleting, moving, renaming or hiding files: needs the app password when that protection is on. */
    fun requireFileOperation(context: Context, onCancel: (() -> Unit)? = null, action: () -> Unit) {
        val store = SecurityStore(context)
        if (!store.lockFileOperations || SecuritySession.operationsUnlocked) { action(); return }
        askAppPassword(context, context.getString(R.string.security_operations_title), onCancel) {
            SecuritySession.operationsUnlocked = true
            action()
        }
    }

    /** Opening a secure folder: needs that folder's own password. */
    fun requireFolder(context: Context, albumKey: String?, albumName: String, action: () -> Unit) {
        val store = SecurityStore(context)
        if (albumKey == null || !store.isSecureFolder(albumKey) || SecuritySession.isFolderUnlocked(albumKey)) {
            action(); return
        }
        Ui.showPasswordDialog(
            context,
            context.getString(R.string.security_folder_title, albumName),
            context.getString(R.string.security_folder_message),
            extraText = context.getString(R.string.security_forgot_password),
            onExtra = { FolderRecovery.recover(context, albumKey, albumName, action) }
        ) { password ->
            if (store.verifyFolderPassword(albumKey, password)) {
                SecuritySession.unlockFolder(albumKey)
                action()
                // Folders protected before the secret question existed get one on their next opening.
                if (!store.hasRecovery(albumKey)) {
                    FolderRecovery.askToCreate(context, listOf(albumKey),
                        context.getString(R.string.security_recovery_add_title, albumName))
                }
                null
            } else context.getString(R.string.security_wrong_password)
        }
    }

    fun askAppPassword(context: Context, title: String, onCancel: (() -> Unit)? = null, onVerified: () -> Unit) {
        val store = SecurityStore(context)
        Ui.showPasswordDialog(context, title, onCancel = onCancel) { password ->
            if (store.verifyAppPassword(password)) {
                onVerified()
                null
            } else context.getString(R.string.security_wrong_password)
        }
    }
}

/**
 * Follows the app between foreground and background. Coming back after the grace period locks
 * everything again and, with "Proteger com senha todo o app", shows the lock screen first.
 */
internal class SecurityLifecycle : Application.ActivityLifecycleCallbacks {
    private var started = 0

    override fun onActivityStarted(activity: Activity) {
        if (started++ == 0) SecuritySession.onForeground(SystemClock.elapsedRealtime())
        if (activity is LockActivity) return
        if (!SecuritySession.appUnlocked && SecurityStore(activity).lockEntireApp) {
            activity.startActivity(Intent(activity, LockActivity::class.java))
            activity.overridePendingTransition(0, 0)
        }
    }

    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        // A rotation stops and restarts the screen; that is not leaving the app.
        if (started == 0 && !activity.isChangingConfigurations) SecuritySession.onBackground(SystemClock.elapsedRealtime())
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
