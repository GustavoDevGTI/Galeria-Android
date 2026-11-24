package com.galeria.android

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * Bottom bar with the shortcuts the user picked; the rest live in the options panel. Each button
 * keeps the tag "browse_<id>". Selection actions belong to their existing dock.
 */
internal class GalleryBrowseBar(
    context: Context,
    bottomInset: Int = 0,
    barTag: String = "gallery_browse_bar",
    private val actionPrefix: String = "browse_",
    private val menuTag: String = MENU_TAG
) : LinearLayout(context) {
    private val dock = Ui.selectionActionDock(context)

    /** Long press on a shortcut; returns true when it started something (a drag). */
    var onShortcutLongPress: ((BrowseAction, View) -> Boolean)? = null

    /** The row holding the shortcuts, where drops land. */
    val shortcutRow: LinearLayout get() = dock

    /** Space for the navigation bar under the buttons. */
    fun setBottomInset(bottomInset: Int) = setPadding(paddingLeft, paddingTop, paddingRight, bottomInset)

    init {
        tag = barTag
        orientation = VERTICAL
        setPadding(0, Ui.dp(context, 1), 0, bottomInset)
        addView(dock, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        restyle()
    }

    /** The shortcuts, then the fixed "Menu" button that opens the options panel (never dragged or removed). */
    fun setActions(actions: List<BrowseAction>, menu: BrowseAction? = null) {
        dock.removeAllViews()
        for (action in actions) {
            Ui.selectionAction(context, action.icon, action.label, action.run).apply {
                tag = "$actionPrefix${action.id}"
                contentDescription = action.description
                isEnabled = action.enabled
                alpha = if (action.enabled) 1f else 0.4f
                isSelected = action.selected
                setOnLongClickListener { view -> onShortcutLongPress?.invoke(action, view) == true }
                Ui.addSelectionActionToDock(dock, this)
            }
        }
        menu?.let { action ->
            Ui.selectionAction(context, action.icon, action.label, action.run).apply {
                tag = menuTag
                contentDescription = action.description
                isSelected = action.selected
                Ui.addSelectionActionToDock(dock, this)
            }
        }
        restyle()
    }

    companion object {
        const val MENU_TAG = "browse_menu"
    }

    fun restyle() {
        setBackgroundColor(Ui.surface(context))
        Ui.restyleSelectionActionDock(dock)
    }
}
