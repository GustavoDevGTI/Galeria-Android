package com.galeria.android

import android.content.Context
import android.view.ViewGroup
import android.widget.LinearLayout

/** Shared browsing actions; selection actions belong to their existing dock. */
internal class GalleryBrowseBar @JvmOverloads constructor(
    context: Context,
    bottomInset: Int = 0,
    onSort: () -> Unit = {},
    onOrganize: () -> Unit = {},
    onHidden: () -> Unit = {},
    onSettings: () -> Unit = {},
    onRandomPlayback: (() -> Unit)? = null
) : LinearLayout(context) {
    private val dock = Ui.selectionActionDock(context)

    init {
        tag = "gallery_browse_bar"
        orientation = VERTICAL
        setPadding(0, Ui.dp(context, 1), 0, bottomInset)
        addView(dock, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fun add(icon: Int, label: Int, description: Int, actionTag: String, action: () -> Unit): LinearLayout =
            Ui.selectionAction(context, icon, context.getString(label), action).apply {
                tag = actionTag
                contentDescription = context.getString(description)
                Ui.addSelectionActionToDock(dock, this)
            }
        add(R.drawable.ic_sort, R.string.browse_sort, R.string.action_sort_by, "browse_sort", onSort)
        add(R.drawable.ic_organize, R.string.action_organize, R.string.action_organize, "browse_organize", onOrganize)
        if (onRandomPlayback != null) {
            add(R.drawable.ic_shuffle, R.string.album_random, R.string.action_random_playback, "browse_shuffle", onRandomPlayback)
        } else {
            add(R.drawable.ic_eye_off, R.string.browse_hidden, R.string.main_folder_visibility, "browse_hidden", onHidden)
        }
        add(R.drawable.ic_settings, R.string.action_settings, R.string.action_settings, "browse_settings", onSettings)
        restyle()
    }

    fun restyle() {
        setBackgroundColor(Ui.surface(context))
        Ui.restyleSelectionActionDock(dock)
    }

}
