package com.galeria.android

import android.content.ClipData
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.DragEvent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * Panel of icons that replaced the three-dot menu. It sits above the bottom bar, which stays
 * visible: holding an icon and dragging it onto the bar makes it a shortcut, dragging a shortcut
 * back into the panel removes it from the bar, and dropping inside the panel only changes the order.
 */
internal class BrowseActionPanel(
    private val activity: ComponentActivity,
    private val bar: GalleryBrowseBar,
    private val store: BrowseLayoutStore,
    private val actions: () -> List<BrowseAction>,
    private val onLayoutChanged: () -> Unit
) {
    private data class DragItem(val id: String, val fromBar: Boolean)

    private var overlay: FrameLayout? = null
    private var grid: LinearLayout? = null
    private val tiles = LinkedHashMap<String, View>()
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = dismiss()
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
        bar.onShortcutLongPress = { action, view ->
            if (overlay != null) startDrag(view, DragItem(action.id, fromBar = true)) else false
        }
        bar.shortcutRow.setOnDragListener { view, event -> onBarDrag(view as LinearLayout, event) }
    }

    val isShowing: Boolean get() = overlay != null

    fun show() {
        if (overlay != null) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        // The overlay ends where the bar starts, so the bar keeps receiving drops.
        val rootLocation = IntArray(2).also(root::getLocationInWindow)
        val barLocation = IntArray(2).also(bar::getLocationInWindow)
        val barTop = if (bar.isShown) barLocation[1] - rootLocation[1] else root.height
        val frame = PanelFrame(activity).apply { tag = "browse_panel_overlay" }
        val scrim = View(activity).apply {
            setBackgroundColor(0x40000000)
            setOnClickListener { dismiss() }
        }
        frame.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = "browse_panel"
            isClickable = true
            background = Ui.rounded(Ui.menuSurface(activity), 22, activity, Ui.menuBorder(activity), 1)
            elevation = dp(12).toFloat()
            setPadding(dp(10), dp(12), dp(10), dp(8))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = "browse_panel_grid"
            setOnDragListener { view, event -> onPanelDrag(view as LinearLayout, event) }
        }
        grid = content
        card.addView(ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        frame.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM).apply {
            marginStart = dp(12)
            marginEnd = dp(12)
            bottomMargin = dp(10)
        })
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barTop, Gravity.TOP))
        overlay = frame
        backCallback.isEnabled = true
        fillGrid()
        // Rises a little from the bar that opened it, with the dimmed background fading in as one surface.
        scrim.alpha = 0f
        scrim.animate().alpha(1f).setDuration(ENTER_MS).setInterpolator(Motion.easeOut).start()
        card.alpha = 0f
        card.translationY = if (Motion.movementEnabled()) dp(16).toFloat() else 0f
        card.animate().alpha(1f).translationY(0f).setDuration(ENTER_MS).setInterpolator(Motion.easeOut).start()
    }

    /** The "Menu" button opens the panel, or closes it when it is already open. */
    fun toggle() = if (overlay != null) dismiss() else show()

    fun dismiss() {
        val frame = overlay ?: return
        overlay = null
        grid = null
        tiles.clear()
        highlighted = null
        backCallback.isEnabled = false
        // Leaves the way it came in. While it fades it no longer takes touches or answers to its tags.
        (frame as? PanelFrame)?.leaving = true
        clearPanelTags(frame)
        val remove = { (frame.parent as? ViewGroup)?.removeView(frame); Unit }
        if (!frame.isAttachedToWindow) { remove(); return }
        val card = frame.getChildAt(1)
        frame.getChildAt(0)?.animate()?.alpha(0f)?.setDuration(Motion.EXIT_MS)?.setInterpolator(Motion.easeOut)?.start()
        card?.animate()?.alpha(0f)
            ?.translationY(if (Motion.movementEnabled()) dp(12).toFloat() else 0f)
            ?.setDuration(Motion.EXIT_MS)?.setInterpolator(Motion.easeOut)
            ?.withEndAction(remove)?.start() ?: remove()
    }

    private fun clearPanelTags(view: View) {
        if ((view.tag as? String)?.startsWith("browse_panel") == true) view.tag = null
        if (view is ViewGroup) for (index in 0 until view.childCount) clearPanelTags(view.getChildAt(index))
    }

    /** The overlay container; once it is leaving, touches and drags pass through to the screen. */
    private class PanelFrame(context: android.content.Context) : FrameLayout(context) {
        var leaving = false
        override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean = !leaving && super.dispatchTouchEvent(event)
        override fun dispatchDragEvent(event: DragEvent): Boolean = !leaving && super.dispatchDragEvent(event)
    }

    /** Lays the panel's options out four per row, in the saved order. */
    private fun fillGrid() {
        val content = grid ?: return
        content.removeAllViews()
        tiles.clear()
        val available = actions().associateBy { it.id }
        val ids = BrowseLayoutRules.panel(store.load(), available.keys)
        ids.chunked(COLUMNS).forEach { rowIds ->
            val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            rowIds.forEach { id ->
                val tile = tile(requireNotNull(available[id]))
                tiles[id] = tile
                row.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            repeat(COLUMNS - rowIds.size) { row.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f)) }
            content.addView(row)
        }
        if (ids.isEmpty()) {
            content.addView(TextView(activity).apply {
                text = activity.getString(R.string.browse_panel_empty)
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(Ui.menuText(activity))
                setPadding(dp(8), dp(24), dp(8), dp(24))
            })
        }
    }

    private fun tile(action: BrowseAction): View = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        tag = "browse_panel_${action.id}"
        contentDescription = action.description
        isClickable = true
        isFocusable = true
        isEnabled = action.enabled
        isSelected = action.selected
        alpha = if (action.enabled) 1f else 0.4f
        minimumHeight = dp(88)
        setPadding(dp(4), dp(10), dp(4), dp(8))
        background = RippleDrawable(
            ColorStateList.valueOf((Ui.menuText(activity) and 0x00FFFFFF) or 0x22000000),
            Ui.rounded(if (action.selected) Ui.menuHighlightSurface(activity) else Color.TRANSPARENT, 16, activity),
            Ui.rounded(Color.WHITE, 16, activity)
        )
        addView(ImageView(activity).apply {
            setImageResource(action.icon)
            imageTintList = ColorStateList.valueOf(Ui.menuText(activity))
            scaleType = ImageView.ScaleType.CENTER
            background = Ui.rounded(Ui.menuOptionSurface(activity), 23, activity)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        addView(TextView(activity).apply {
            text = action.label
            textSize = 12f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(Ui.menuText(activity))
            if (action.selected) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(0, dp(6), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setOnClickListener {
            dismiss()
            bar.post(action.run)
        }
        setOnLongClickListener { view -> startDrag(view, DragItem(action.id, fromBar = false)) }
    }

    private fun startDrag(view: View, item: DragItem): Boolean {
        Haptics.perform(view, Haptics.Effect.TICK)
        val data = ClipData.newPlainText("browse_action", item.id)
        val shadow = View.DragShadowBuilder(view)
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            view.startDragAndDrop(data, shadow, item, 0)
        } else {
            @Suppress("DEPRECATION") view.startDrag(data, shadow, item, 0)
        }
    }

    private fun onPanelDrag(content: LinearLayout, event: DragEvent): Boolean {
        val item = event.localState as? DragItem ?: return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                tiles[item.id]?.alpha = 0.3f
                return true
            }
            DragEvent.ACTION_DRAG_LOCATION -> highlight(tileAt(content, event.x, event.y))
            DragEvent.ACTION_DRAG_EXITED -> highlight(null)
            DragEvent.ACTION_DROP -> {
                highlight(null)
                val before = tileAt(content, event.x, event.y)?.takeIf { it != item.id }
                val layout = store.load()
                val (next, result) = if (item.fromBar) {
                    BrowseLayoutRules.removeFromBar(layout, item.id, before)
                } else {
                    BrowseLayoutRules.movePanel(layout, item.id, before)
                }
                apply(next, result)
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                highlight(null)
                if (overlay != null) fillGrid()
            }
        }
        return true
    }

    private fun onBarDrag(row: LinearLayout, event: DragEvent): Boolean {
        val item = event.localState as? DragItem ?: return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> return overlay != null
            DragEvent.ACTION_DRAG_ENTERED -> row.background = Ui.rounded(Ui.menuHighlightSurface(activity), 16, activity)
            DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> bar.restyle()
            DragEvent.ACTION_DROP -> {
                bar.restyle()
                // Insertion point: how many shortcuts sit left of the finger; never after the fixed Menu button.
                var index = 0
                for (child in 0 until row.childCount) {
                    val view = row.getChildAt(child)
                    if (view.tag == GalleryBrowseBar.MENU_TAG) continue
                    if (event.x > view.left + view.width / 2f) index = child + 1
                }
                val layout = store.load()
                val (next, result) = if (item.fromBar) {
                    BrowseLayoutRules.moveInBar(layout, item.id, index)
                } else {
                    BrowseLayoutRules.addToBar(layout, item.id, index)
                }
                apply(next, result)
                return true
            }
        }
        return true
    }

    private fun apply(layout: BrowseLayoutRules.Layout, result: BrowseLayoutRules.Result) {
        when (result) {
            BrowseLayoutRules.Result.DONE -> {
                store.save(layout)
                onLayoutChanged()
                Haptics.perform(bar, Haptics.Effect.CONFIRM)
            }
            BrowseLayoutRules.Result.BAR_FULL ->
                Ui.toast(activity, activity.getString(R.string.browse_bar_full, BrowseLayoutRules.MAX_BAR))
            BrowseLayoutRules.Result.BAR_MIN -> Ui.toast(activity, activity.getString(R.string.browse_bar_min))
            BrowseLayoutRules.Result.UNCHANGED -> Unit
        }
        fillGrid()
    }

    private var highlighted: String? = null

    private fun highlight(id: String?) {
        if (highlighted == id) return
        highlighted?.let { tiles[it]?.let { tile -> tile.isActivated = false; scaleTile(tile, 1f) } }
        highlighted = id
        id?.let { tiles[it]?.let { tile -> scaleTile(tile, 1.06f) } }
    }

    /** The drop target grows a little under the finger, quickly enough to follow it. */
    private fun scaleTile(tile: View, scale: Float) {
        tile.animate().cancel()
        if (!Motion.movementEnabled()) { tile.scaleX = scale; tile.scaleY = scale; return }
        tile.animate().scaleX(scale).scaleY(scale).setDuration(120L).setInterpolator(Motion.easeOut).start()
    }

    private fun tileAt(content: LinearLayout, x: Float, y: Float): String? {
        val rect = Rect()
        for ((id, tile) in tiles) {
            tile.getDrawingRect(rect)
            content.offsetDescendantRectToMyCoords(tile, rect)
            if (rect.contains(x.toInt(), y.toInt())) return id
        }
        return null
    }

    private fun dp(value: Int) = Ui.dp(activity, value)

    private companion object {
        const val COLUMNS = 4
        const val ENTER_MS = 200L
    }
}
