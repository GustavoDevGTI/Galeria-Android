package com.galeria.android

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PointF
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
        cancelDrag()
        overlay = null
        grid = null
        tiles.clear()
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
        // Touch feedback lives on the icon's circle only, never as a box around the whole option.
        background = null
        addView(ImageView(activity).apply {
            tag = TILE_ICON_TAG
            setImageResource(action.icon)
            imageTintList = ColorStateList.valueOf(Ui.menuText(activity))
            scaleType = ImageView.ScaleType.CENTER
            background = RippleDrawable(
                ColorStateList.valueOf((Ui.menuText(activity) and 0x00FFFFFF) or 0x33000000),
                Ui.rounded(if (action.selected) Ui.menuHighlightSurface(activity) else Ui.menuOptionSurface(activity), 23, activity),
                Ui.rounded(Color.WHITE, 23, activity)
            )
            // Pressing anywhere on the option lights its circle.
            isDuplicateParentStateEnabled = true
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

    /**
     * An option being carried, like an app icon on a phone's home screen: its circle follows the
     * finger, its place stays empty, and the other options slide into the order it would leave
     * while it is still held. Letting go settles it in place; letting go outside sends it back.
     */
    private inner class Drag(val item: DragItem, val source: View, val picture: android.graphics.drawable.BitmapDrawable) {
        /** The panel order and the place of each option when the drag started (window coordinates). */
        val baseIds: List<String> = tiles.keys.toList()
        val baseRects: Map<String, RectF> = tiles.mapValues { windowRect(it.value) }
        var finger = PointF()
        /** Where the option would land in the panel now (index in the panel without it), or null. */
        var panelIndex: Int? = null
        var shownPanel: List<String> = baseIds
    }

    private var drag: Drag? = null

    private fun startDrag(view: View, item: DragItem): Boolean {
        if (drag != null) return false
        val action = actions().firstOrNull { it.id == item.id } ?: return false
        Haptics.perform(view, Haptics.Effect.TICK)
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val picture = android.graphics.drawable.BitmapDrawable(activity.resources, liftedPicture(action))
        val current = Drag(item, view, picture)
        drag = current
        // The touch that started the long press keeps coming to this view: follow it from here.
        view.isPressed = false
        view.parent?.requestDisallowInterceptTouchEvent(true)
        view.setOnTouchListener { touched, event -> followFinger(touched, event) }
        current.finger = windowRect(view).let { PointF(it.centerX(), it.centerY()) }
        root.overlay.add(picture)
        placePicture(current, LIFT_SCALE)
        // Its place stays empty while it is carried.
        view.animate().cancel()
        view.alpha = 0f
        return true
    }

    /** The option as carried: its icon on a round chip with a soft shadow, nothing around it. */
    private fun liftedPicture(action: BrowseAction): android.graphics.Bitmap {
        val chip = dp(52)
        val margin = dp(8)
        val size = chip + margin * 2
        val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.menuOptionSurface(activity)
            setShadowLayer(dp(5).toFloat(), 0f, dp(2).toFloat(), 0x55000000)
        }
        canvas.drawCircle(size / 2f, size / 2f, chip / 2f, paint)
        paint.clearShadowLayer()
        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = dp(1).toFloat()
        paint.color = Ui.menuBorder(activity)
        canvas.drawCircle(size / 2f, size / 2f, chip / 2f, paint)
        activity.getDrawable(action.icon)?.mutate()?.let { icon ->
            icon.setTint(Ui.menuText(activity))
            val half = dp(13)
            icon.setBounds(size / 2 - half, size / 2 - half, size / 2 + half, size / 2 + half)
            icon.draw(canvas)
        }
        return bitmap
    }

    /** Puts the carried picture under the finger (root coordinates of the screen). */
    private fun placePicture(current: Drag, scale: Float) {
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val rootRect = windowRect(root)
        val half = current.picture.bitmap.width * scale / 2f
        val x = current.finger.x - rootRect.left
        val y = current.finger.y - rootRect.top
        current.picture.setBounds((x - half).toInt(), (y - half).toInt(), (x + half).toInt(), (y + half).toInt())
    }

    private fun followFinger(view: View, event: android.view.MotionEvent): Boolean {
        val current = drag ?: return false.also { view.setOnTouchListener(null) }
        // The event comes in the source view's own coordinates (its slide already undone), and its
        // window position includes that slide; together they give the finger on the screen.
        val origin = windowRect(view)
        current.finger = PointF(origin.left + event.x, origin.top + event.y)
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_MOVE -> {
                placePicture(current, LIFT_SCALE)
                reflow(current)
            }
            android.view.MotionEvent.ACTION_UP -> drop(current)
            android.view.MotionEvent.ACTION_CANCEL -> cancelDrag()
        }
        return true
    }

    /** Where the finger is: over the bar, over the panel, or neither. */
    private enum class Zone { BAR, PANEL, NONE }

    private fun zoneOf(point: PointF): Zone {
        val row = bar.shortcutRow
        if (row.isShown && windowRect(row).apply { inset(0f, -dp(12).toFloat()) }.contains(point.x, point.y)) return Zone.BAR
        val content = grid ?: return Zone.NONE
        val card = (content.parent as? View)?.parent as? View ?: content
        return if (windowRect(card).contains(point.x, point.y)) Zone.PANEL else Zone.NONE
    }

    /**
     * Shows, while the option is held, where everything goes: over the bar its buttons open room;
     * over the panel the options slide so the one under the finger makes way into the empty place.
     */
    private fun reflow(current: Drag) {
        val zone = zoneOf(current.finger)
        val row = bar.shortcutRow
        openRoom(row, current.item, if (zone == Zone.BAR) insertionIndex(row, windowToView(row, current.finger)) else null)
        val others = current.baseIds - current.item.id
        current.panelIndex = if (zone == Zone.PANEL) nearestSlot(current, others.size + 1) else null
        val order = when {
            current.panelIndex != null -> others.toMutableList().apply { add(current.panelIndex!!, current.item.id) }
            // Carried over the bar, a panel option leaves its place: the others close the gap.
            zone == Zone.BAR && !current.item.fromBar -> others
            else -> current.baseIds
        }
        if (order == current.shownPanel) return
        current.shownPanel = order
        Haptics.perform(row, Haptics.Effect.TICK)
        tiles.forEach { (id, tile) ->
            if (id == current.item.id) return@forEach
            val slot = order.indexOf(id)
            val from = current.baseRects[id] ?: return@forEach
            val to = slotRect(current, slot) ?: from
            tile.animate().translationX(to.left - from.left).translationY(to.top - from.top)
                .setDuration(if (Motion.movementEnabled()) ROOM_MS else 0L).setInterpolator(Motion.easeOut).start()
        }
    }

    /** The panel place nearest the finger, among [count] places. */
    private fun nearestSlot(current: Drag, count: Int): Int {
        var best = 0
        var bestDistance = Float.MAX_VALUE
        for (index in 0 until count) {
            val rect = slotRect(current, index) ?: continue
            val dx = rect.centerX() - current.finger.x
            val dy = rect.centerY() - current.finger.y
            val distance = dx * dx + dy * dy
            if (distance < bestDistance) { bestDistance = distance; best = index }
        }
        return best
    }

    /**
     * The place of the [index]-th option in the panel grid, from where the options were laid out
     * (four per row); a place past the last one continues the grid.
     */
    private fun slotRect(current: Drag, index: Int): RectF? {
        val rects = current.baseIds.mapNotNull { current.baseRects[it] }
        if (index < 0 || rects.isEmpty()) return null
        rects.getOrNull(index)?.let { return it }
        val first = rects[0]
        val columnRect = rects.getOrNull(index % COLUMNS) ?: RectF(first).apply {
            val step = (rects.getOrNull(1)?.left ?: (first.right + dp(6))) - first.left
            offset(step * (index % COLUMNS), 0f)
        }
        val rowStep = (rects.getOrNull(COLUMNS)?.top ?: (first.bottom + dp(6))) - first.top
        return RectF(columnRect).apply { offsetTo(columnRect.left, first.top + rowStep * (index / COLUMNS)) }
    }

    private fun windowToView(view: View, point: PointF): Float = point.x - windowRect(view).left

    /** Lets go: the option lands where the reflow showed, or goes back if held outside both. */
    private fun drop(current: Drag) {
        val zone = zoneOf(current.finger)
        val item = current.item
        val layout = store.load()
        val change = when (zone) {
            Zone.BAR -> {
                val index = insertionIndex(bar.shortcutRow, windowToView(bar.shortcutRow, current.finger))
                if (item.fromBar) BrowseLayoutRules.moveInBar(layout, item.id, index) else BrowseLayoutRules.addToBar(layout, item.id, index)
            }
            Zone.PANEL -> {
                val others = current.baseIds - item.id
                val before = current.panelIndex?.let { others.getOrNull(it) }
                if (item.fromBar) BrowseLayoutRules.removeFromBar(layout, item.id, before) else BrowseLayoutRules.movePanel(layout, item.id, before)
            }
            Zone.NONE -> null
        }
        if (change == null) { cancelDrag(); return }
        endDrag(current, keepPicture = false)
        apply(change.first, change.second, item.id, current.finger)
    }

    /** Back where it came from: the picture glides home and every option returns to its place. */
    private fun cancelDrag() {
        val current = drag ?: return
        endDrag(current, keepPicture = true)
        openRoom(bar.shortcutRow, current.item, null)
        tiles.values.forEach { it.animate().translationX(0f).translationY(0f).setDuration(ROOM_MS).setInterpolator(Motion.easeOut).start() }
        val home = windowRect(current.source)
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val start = current.finger
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (Motion.movementEnabled()) GLIDE_MS else 0L
            interpolator = Motion.easeOut
            addUpdateListener {
                val f = it.animatedValue as Float
                current.finger = PointF(start.x + (home.centerX() - start.x) * f, start.y + (home.centerY() - start.y) * f)
                placePicture(current, LIFT_SCALE + (1f - LIFT_SCALE) * f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    root.overlay.remove(current.picture)
                    current.source.animate().alpha(1f).setDuration(Motion.EXIT_MS).start()
                }
            })
            start()
        }
    }

    private fun endDrag(current: Drag, keepPicture: Boolean) {
        drag = null
        current.source.setOnTouchListener(null)
        current.source.parent?.requestDisallowInterceptTouchEvent(false)
        if (!keepPicture) activity.findViewById<ViewGroup>(android.R.id.content).overlay.remove(current.picture)
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

    private fun dp(value: Int) = Ui.dp(activity, value)

    private companion object {
        const val COLUMNS = 4
        const val ENTER_MS = 200L
        /** The carried option a little bigger than at rest, as if lifted by the finger. */
        const val LIFT_SCALE = 1.12f
        const val TILE_ICON_TAG = "browse_panel_icon"
        /** The buttons sliding apart under the finger. */
        const val ROOM_MS = 160L
        /** A button travelling to its new place after the drop. */
        const val GLIDE_MS = 280L
    }
}
