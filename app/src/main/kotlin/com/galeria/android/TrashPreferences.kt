package com.galeria.android

import android.content.Context

internal object TrashPreferences {
    const val ENABLED = "trash_enabled"

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(Ui.PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, true)

    fun deletePermanently(context: Context, albumKey: String?): Boolean =
        albumKey == VirtualAlbumRules.TRASH_KEY || !isEnabled(context)
}
