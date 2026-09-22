package com.galeria.android

import android.content.SharedPreferences
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.edit

class MainMediaAccessCoordinator(
    private val activity: ComponentActivity,
    private val prefs: SharedPreferences
) {
    fun start(onLibraryReady: () -> Unit) {
        when (
            MainAccessRules.startupAction(
                hasMediaLibraryAccess(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                initialChoiceMade()
            )
        ) {
            MainAccessStartupAction.LOAD_LIBRARY -> onLibraryReady()
            MainAccessStartupAction.SHOW_INITIAL_CHOICE -> showInitialChoice()
            MainAccessStartupAction.REQUEST_MEDIA_LIBRARY -> requestMediaLibraryAccess()
        }
    }

    fun hasMediaLibraryAccess(): Boolean = MediaActions.hasMediaLibraryAccess(activity)

    fun initialChoiceMade(): Boolean = prefs.getBoolean(PREF_INITIAL_ACCESS_CHOSEN, false)

    fun includeHiddenFilesystem(requested: Boolean): Boolean =
        StorageAccessRules.includeHiddenFilesystem(requested, MediaActions.hasAllFilesAccess(activity))

    fun onRequestPermissionsResult(requestCode: Int, onGranted: () -> Unit, onDenied: () -> Unit): Boolean {
        if (requestCode != REQUEST_MEDIA_LIBRARY) return false
        if (hasMediaLibraryAccess()) onGranted() else onDenied()
        return true
    }

    fun ensureFullAccess(force: Boolean) {
        if (MediaActions.hasAllFilesAccess(activity)) return
        if (!force && prefs.getBoolean(PREF_ALL_FILES_PROMPTED, false)) return
        Ui.showConfirmationDialog(
            activity,
            activity.getString(R.string.access_full_title),
            activity.getString(R.string.access_full_explanation),
            activity.getString(R.string.action_allow),
            negativeText = activity.getString(R.string.action_not_now),
            onNegative = { markFullAccessPrompted() }
        ) {
            markFullAccessPrompted()
            MediaActions.requestAllFilesAccess(activity)
        }
    }

    private fun showInitialChoice() {
        val dialog = Ui.showActionChoiceDialog(
            activity,
            activity.getString(R.string.access_initial_choice_title),
            activity.getString(R.string.access_initial_choice_message),
            activity.getString(R.string.access_use_standard),
            activity.getString(R.string.access_use_full),
            onFirst = {
                markInitialChoiceMade()
                requestMediaLibraryAccess()
            },
            onSecond = {
                markInitialChoiceMade()
                MediaActions.requestAllFilesAccess(activity)
            }
        )
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
    }

    private fun requestMediaLibraryAccess() {
        activity.requestPermissions(MediaActions.mediaLibraryPermissions(), REQUEST_MEDIA_LIBRARY)
    }

    private fun markInitialChoiceMade() {
        prefs.edit { putBoolean(PREF_INITIAL_ACCESS_CHOSEN, true) }
    }

    private fun markFullAccessPrompted() {
        prefs.edit { putBoolean(PREF_ALL_FILES_PROMPTED, true) }
    }

    private companion object {
        const val REQUEST_MEDIA_LIBRARY = 10
        const val PREF_ALL_FILES_PROMPTED = "all_files_prompted"
        const val PREF_INITIAL_ACCESS_CHOSEN = "initial_access_chosen"
    }
}
