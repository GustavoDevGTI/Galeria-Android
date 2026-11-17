package com.galeria.android

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * Bottom bar with the shortcuts the user picked; the rest live in the options panel. Each button
 * keeps the tag "browse_<id>". Selection actions belong to their existing dock.
 */
internal class GalleryBrowseBar(context: Context, bottomInset: Int = 0) : LinearLayout(context) {
    private val dock = Ui.selectionActionDock(context)

    /** Long press on a shortcut; returns true when it started something (a drag). */
    var onShortcutLongPress: ((BrowseAction, View) -> Boolean)? = null

    /** The row holding the shortcuts, where drops land. */
    val shortcutRow: LinearLayout get() = dock

    /** Space for the navigation bar under the buttons. */
    fun setBottomInset(bottomInset: Int) = setPadding(paddingLeft, paddingTop, paddingRight, bottomInset)

    init {
        tag = "gallery_browse_bar"
        orientation = VERTICAL
        setPadding(0, Ui.dp(context, 1), 0, bottomInset)
        addView(dock, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        restyle()
    }

    fun setActions(actions: List<BrowseAction>) {
        dock.removeAllViews()
        for (action in actions) {
            Ui.selectionAction(context, action.icon, action.label, action.run).apply {
                tag = "browse_${action.id}"
                contentDescription = action.description
                isEnabled = action.enabled
                alpha = if (action.enabled) 1f else 0.4f
                isSelected = action.selected
                setOnLongClickListener { view -> onShortcutLongPress?.invoke(action, view) == true }
                Ui.addSelectionActionToDock(dock, this)
            }
        }
        restyle()
    }

    fun restyle() {
        setBackgroundColor(Ui.surface(context))
        Ui.restyleSelectionActionDock(dock)
    }
}
