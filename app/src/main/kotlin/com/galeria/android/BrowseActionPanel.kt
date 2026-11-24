package com.galeria.android

import android.content.ClipData
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
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
                apply(next, result, item.id, windowPoint(content, event.x, event.y))
                return true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                highlight(null)
                // Dropped nowhere: the option fades back where it was. A real drop already rebuilt the panel.
                if (!event.result) tiles[item.id]?.animate()?.alpha(1f)?.setDuration(Motion.EXIT_MS)?.start()
            }
        }
        return true
    }

    private fun onBarDrag(row: LinearLayout, event: DragEvent): Boolean {
        val item = event.localState as? DragItem ?: return false
        when (event.action) {
            DragEvent.ACTION_DRAG_STARTED -> {
                if (overlay == null) return false
                if (item.fromBar) bar.viewsById()[item.id]?.alpha = 0.3f
                return true
            }
            DragEvent.ACTION_DRAG_ENTERED -> row.background = Ui.rounded(Ui.menuHighlightSurface(activity), 16, activity)
            DragEvent.ACTION_DRAG_LOCATION -> openRoom(row, item, insertionIndex(row, event.x))
            DragEvent.ACTION_DRAG_EXITED -> { bar.restyle(); openRoom(row, item, null) }
            DragEvent.ACTION_DRAG_ENDED -> {
                bar.restyle()
                openRoom(row, item, null)
                if (!event.result) bar.viewsById()[item.id]?.animate()?.alpha(1f)?.setDuration(Motion.EXIT_MS)?.start()
            }
            DragEvent.ACTION_DROP -> {
                bar.restyle()
                val index = insertionIndex(row, event.x)
                val layout = store.load()
                val (next, result) = if (item.fromBar) {
                    BrowseLayoutRules.moveInBar(layout, item.id, index)
                } else {
                    BrowseLayoutRules.addToBar(layout, item.id, index)
                }
                apply(next, result, item.id, windowPoint(row, event.x, event.y))
                return true
            }
        }
        return true
    }

    /** How many shortcuts sit left of the finger; never after the fixed Menu button. */
    private fun insertionIndex(row: LinearLayout, x: Float): Int {
        var index = 0
        for (child in 0 until row.childCount) {
            val view = row.getChildAt(child)
            if (view.tag == GalleryBrowseBar.MENU_TAG) continue
            if (x > view.left + view.width / 2f) index = child + 1
        }
        return index
    }

    private var roomIndex: Int? = null

    /**
     * While an option is held over the bar, the buttons slide apart to show where it will land,
     * like icons making room on a phone's home screen; [index] null closes the room again.
     */
    private fun openRoom(row: LinearLayout, item: DragItem, index: Int?) {
        if (roomIndex == index) return
        roomIndex = index
        val views = bar.viewsById()
        val preview = index?.let { BrowseLayoutRules.previewBar(store.load(), item.id, item.fromBar, it) }
        val slots = preview?.let { it + GalleryBrowseBar.MENU_ID }
        val centers = slots?.let {
            BrowseLayoutRules.slotCenters(it.size, row.paddingLeft.toFloat(), (row.width - row.paddingLeft - row.paddingRight).toFloat())
        }
        views.forEach { (id, view) ->
            val target = if (slots == null || centers == null) 0f else {
                val slot = slots.indexOf(id)
                if (slot < 0) 0f else centers[slot] - (view.left + view.width / 2f)
            }
            view.animate().translationX(target).setDuration(if (Motion.movementEnabled()) ROOM_MS else 0L)
                .setInterpolator(Motion.easeOut).start()
        }
    }

    private fun windowPoint(view: View, x: Float, y: Float): PointF {
        val location = IntArray(2).also(view::getLocationInWindow)
        return PointF(location[0] + x, location[1] + y)
    }

    private fun apply(layout: BrowseLayoutRules.Layout, result: BrowseLayoutRules.Result,
        droppedId: String? = null, dropPoint: PointF? = null) {
        roomIndex = null
        val before = buttonRects()
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
        // Even when nothing changed, the bar is drawn again: the held button gets its look back.
        if (result != BrowseLayoutRules.Result.DONE) onLayoutChanged()
        fillGrid()
        glide(before, if (result == BrowseLayoutRules.Result.DONE) droppedId else null, dropPoint)
    }

    /** Where every button of the bar and the panel is now, in window coordinates. */
    private fun buttonRects(): Map<String, RectF> = buildMap {
        bar.viewsById().forEach { (id, view) -> put("bar:$id", windowRect(view)) }
        tiles.forEach { (id, view) -> put("panel:$id", windowRect(view)) }
    }

    private fun windowRect(view: View): RectF {
        val location = IntArray(2).also(view::getLocationInWindow)
        return RectF(location[0].toFloat(), location[1].toFloat(), location[0] + view.width.toFloat(), location[1] + view.height.toFloat())
    }

    /**
     * After the bar and the panel are rebuilt, each button glides from where it was to its new
     * place, and the dropped one from under the finger. The moving pictures are drawn over the
     * whole screen, so a button can travel between the panel and the bar without being cut off.
     */
    private fun glide(before: Map<String, RectF>, droppedId: String?, dropPoint: PointF?) {
        if (!Motion.movementEnabled()) return
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        root.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                root.viewTreeObserver.removeOnPreDrawListener(this)
                val rootRect = windowRect(root)
                val moves = ArrayList<Glide>()
                val after = bar.viewsById().mapKeys { "bar:${it.key}" } + tiles.mapKeys { "panel:${it.key}" }
                after.forEach { (key, view) ->
                    if (view.width == 0 || view.height == 0) return@forEach
                    val id = key.substringAfter(':')
                    val to = windowRect(view)
                    val from = if (id == droppedId && dropPoint != null) {
                        RectF(dropPoint.x - to.width() / 2f, dropPoint.y - to.height() / 2f, dropPoint.x + to.width() / 2f, dropPoint.y + to.height() / 2f)
                    } else before[key] ?: before["bar:$id"] ?: before["panel:$id"] ?: return@forEach
                    if (id != droppedId && kotlin.math.abs(from.centerX() - to.centerX()) < 1f && kotlin.math.abs(from.centerY() - to.centerY()) < 1f) return@forEach
                    val picture = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(picture))
                    val drawable = android.graphics.drawable.BitmapDrawable(activity.resources, picture).apply { alpha = (view.alpha * 255).toInt() }
                    from.offset(-rootRect.left, -rootRect.top)
                    to.offset(-rootRect.left, -rootRect.top)
                    moves += Glide(view, view.alpha, drawable, from, to, id == droppedId)
                    view.alpha = 0f
                    root.overlay.add(drawable)
                }
                if (moves.isEmpty()) return true
                val bounds = android.graphics.Rect()
                fun place(f: Float) = moves.forEach { move ->
                    // The dropped button starts a little bigger, as if set down from the finger.
                    val grow = if (move.dropped) 0.12f * (1f - f) else 0f
                    val w = move.to.width() * (1f + grow)
                    val h = move.to.height() * (1f + grow)
                    val cx = move.from.centerX() + (move.to.centerX() - move.from.centerX()) * f
                    val cy = move.from.centerY() + (move.to.centerY() - move.from.centerY()) * f
                    bounds.set((cx - w / 2f).toInt(), (cy - h / 2f).toInt(), (cx + w / 2f).toInt(), (cy + h / 2f).toInt())
                    move.drawable.bounds = bounds
                }
                place(0f)
                android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = GLIDE_MS
                    interpolator = Motion.easeOut
                    addUpdateListener { place(it.animatedValue as Float) }
                    addListener(object : android.animation.AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: android.animation.Animator) {
                            moves.forEach { move ->
                                root.overlay.remove(move.drawable)
                                move.view.alpha = move.alpha
                            }
                        }
                    })
                    start()
                }
                return true
            }
        })
    }

    private class Glide(val view: View, val alpha: Float, val drawable: android.graphics.drawable.BitmapDrawable,
        val from: RectF, val to: RectF, val dropped: Boolean)

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
        /** The buttons sliding apart under the finger. */
        const val ROOM_MS = 160L
        /** A button travelling to its new place after the drop. */
        const val GLIDE_MS = 280L
    }
}
