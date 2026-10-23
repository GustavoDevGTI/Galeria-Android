package com.galeria.android

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import coil3.load
import coil3.request.allowHardware
import coil3.request.crossfade
import kotlin.math.*

/** Native view: preloaded image views are promoted, never replaced by a loading placeholder. */
@SuppressLint("ViewConstructor") // Programmatic screen; a retained session is required.
internal class OrganizerBoard(context: Context, private val controller: OrganizerController) : FrameLayout(context) {
    private val ring = RingView(context)
    private val pictures = LinkedHashMap<String, ImageView>()
    private val labels = ArrayList<TextView>()
    private val reserve = FrameLayout(context)
    private val compact = LinearLayout(context)
    private val reservePreview = ImageView(context)
    private val reserveLabel = Ui.label(context, "")
    private val carousel = LinearLayout(context)
    private val scroll = ReserveRecyclerView(context)
    private val reserveAdapter = ReserveAdapter()
    private val reserveEmpty = Ui.label(context, "Arraste uma mídia para cá")
    private val empty = Ui.label(context, context.getString(R.string.organizer_empty))
    private val hint = Ui.label(context, context.getString(R.string.organizer_hint))
    private var reserveIds = emptyList<String>()
    private var selectedReserved: String? = null
    private var reserveProgress = if (controller.reserveOpen) 1f else 0f
    private var reserveAnimator: ValueAnimator? = null
    private var diameter = 0f
    private var cx = 0f
    private var cy = 0f
    private var sideLayout = false
    private var reservePaneWidth = 0
    private var hover = -1
    private var topId: String? = null
    private var activeGhost: ImageView? = null
    private var reorderGhost: TextView? = null
    private val location = IntArray(2)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    init {
        tag = "organizer_board"; clipChildren = false; clipToPadding = false
        addView(ring)
        installReordering(ring, -1)
        empty.setPadding(dp(36), 0, dp(36), 0); empty.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(empty)
        repeat(OrganizerRules.MAX_DESTINATIONS) { index ->
            val label = Ui.label(context, "").apply {
                tag = "organizer_destination_$index"; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE; textSize = 12f
                isClickable = true; isFocusable = true
                setOnClickListener {
                    val album = controller.destinations.getOrNull(index) ?: return@setOnClickListener
                    val send = context.getString(R.string.organizer_send_to, album.name)
                    val swap = "Trocar posição…"
                    Ui.showPopupOptions(this, listOf(send, swap)) {
                        if (it == send) controller.media.queue.firstOrNull()?.let { item -> launch(item, false, index, null) }
                        else chooseSwap(index, this)
                    }
                }
                setOnLongClickListener { chooseSwap(index, this); true }
                installReordering(this, index)
            }
            labels.add(label); addView(label)
        }
        reserve.apply {
            tag = "organizer_reserve"; background = Ui.rounded(OrganizerColors.surface(context), 14, context)
            contentDescription = context.getString(R.string.organizer_reserve, 0)
        }
        compact.apply {
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL; isClickable = true; isFocusable = true
            tag = "organizer_reserve_button"; setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { morphReserve(true) }
        }
        reservePreview.scaleType = ImageView.ScaleType.CENTER_CROP
        reservePreview.setImageResource(R.drawable.ic_folder); reservePreview.setColorFilter(Ui.text(context))
        reservePreview.setPadding(dp(14), dp(14), dp(14), dp(14))
        compact.addView(reservePreview, LinearLayout.LayoutParams(dp(52), dp(52)))
        compact.addView(reserveLabel, LinearLayout.LayoutParams(0, -1, 1f))
        reserve.addView(compact, LayoutParams(-1, -1))
        carousel.gravity = Gravity.CENTER_VERTICAL
        scroll.apply {
            isHorizontalScrollBarEnabled = false; clipToPadding = false
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = reserveAdapter; itemAnimator = null
            tag = "organizer_reserve_carousel"; setPadding(dp(8), dp(7), 0, dp(7))
        }
        carousel.addView(scroll, LinearLayout.LayoutParams(0, -1, 1f))
        carousel.addView(reserveEmpty, LinearLayout.LayoutParams(0, -1, 1f))
        carousel.addView(ImageButton(context).apply {
            setImageResource(R.drawable.ic_back); setColorFilter(Ui.text(context)); setBackgroundColor(Color.TRANSPARENT)
            contentDescription = context.getString(R.string.organizer_close_reserve)
            setOnClickListener { morphReserve(false) }
        }, LinearLayout.LayoutParams(dp(44), -1))
        reserve.addView(carousel, LayoutParams(-1, -1))
        addView(reserve)
        hint.textSize = 12f; hint.setPadding(dp(18), dp(4), dp(18), dp(4)); addView(hint)
        render()
    }

    fun render() {
        val previous = topId
        val next = controller.media.queue.take(12)
        val keep = next.map { it.uri.toString() }.toSet()
        pictures.keys.filter { it !in keep }.toList().forEach { key -> removeView(pictures.remove(key)) }
        next.forEach { item ->
            val key = item.uri.toString()
            pictures.getOrPut(key) {
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = if (item.isVideo()) "Vídeo para organizar" else "Foto para organizar"
                    visibility = INVISIBLE
                    addView(this, 1, LayoutParams(dp(160), dp(160)))
                    load(item.uri) {
                        size(512); crossfade(false); allowHardware(false)
                        ImageRotation.configureRequest(context, item, this)
                        listener(onError = { _, _ ->
                            // A failed decode is explicit, never confused with an empty queue.
                            setImageResource(if (item.isVideo()) R.drawable.ic_play else R.drawable.ic_view_media)
                        })
                    }
                    installMediaGesture(this, item, false)
                }
            }
        }
        pictures.forEach { (key, view) -> view.visibility = INVISIBLE; view.isEnabled = false; view.tag = "organizer_preload_$key" }
        next.take(3).asReversed().forEach { item ->
            pictures[item.uri.toString()]?.apply {
                visibility = VISIBLE; bringToFront(); isEnabled = item === next.firstOrNull()
                if (isEnabled) tag = "organizer_current"
            }
        }
        topId = next.firstOrNull()?.uri?.toString()
        if (previous != null && topId != previous) pictures[topId]?.apply {
            animate().cancel(); alpha = 1f
            if (android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()) {
                scaleX = .96f; scaleY = .96f
                animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
        }
        empty.visibility = if (next.isEmpty()) VISIBLE else GONE
        labels.forEachIndexed { index, label ->
            val album = controller.destinations.getOrNull(index)
            label.visibility = if (album == null) GONE else VISIBLE
            label.text = album?.name.orEmpty()
            label.contentDescription = album?.let { context.getString(R.string.organizer_send_to, it.name) }
            label.setTextColor(if (album?.key == controller.lastAlbum) OrganizerColors.highlightText(context) else Ui.text(context))
        }
        reserveLabel.text = context.getString(R.string.organizer_reserve, controller.media.reserved.size)
        compact.contentDescription = reserveLabel.text
        val recent = controller.media.reserved.lastOrNull()
        if (reservePreview.tag != recent?.uri) {
            reservePreview.tag = recent?.uri
            if (recent == null) {
                reservePreview.setPadding(dp(14), dp(14), dp(14), dp(14))
                reservePreview.setImageResource(R.drawable.ic_folder); reservePreview.setColorFilter(Ui.text(context))
            } else {
                reservePreview.setPadding(0, 0, 0, 0); reservePreview.clearColorFilter()
                reservePreview.load(recent.uri) { size(128); crossfade(false) }
            }
        }
        val ids = controller.media.reserved.map { it.uri.toString() }
        if (ids != reserveIds) {
            val oldIds = reserveIds
            reserveIds = ids
            if (selectedReserved !in ids) selectedReserved = null
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = oldIds.size
                override fun getNewListSize() = ids.size
                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int) = oldIds[oldItemPosition] == ids[newItemPosition]
                override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int) = true
            }).dispatchUpdatesTo(reserveAdapter)
        }
        compact.visibility = if (controller.reserveOpen) GONE else VISIBLE
        carousel.visibility = if (controller.reserveOpen) VISIBLE else GONE
        scroll.visibility = if (ids.isEmpty()) GONE else VISIBLE
        reserveEmpty.visibility = if (ids.isEmpty()) VISIBLE else GONE
        reserve.bringToFront(); activeGhost?.bringToFront(); reorderGhost?.bringToFront(); hint.bringToFront()
        ring.invalidate(); requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = MeasureSpec.getSize(heightMeasureSpec)
        val desired = min(width, dp(480)) + dp(140)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) available else max(available, desired)
        setMeasuredDimension(width, height)
        sideLayout = width > height
        reservePaneWidth = if (sideLayout) (width * .42f).roundToInt() else width
        diameter = if (sideLayout) min(width - reservePaneWidth - dp(20), height - dp(16)).coerceAtLeast(dp(80)).toFloat()
            else min(min(width - dp(20), height - dp(138)), dp(480)).coerceAtLeast(dp(80)).toFloat()
        cx = if (sideLayout) reservePaneWidth + (width - reservePaneWidth) / 2f else width / 2f
        cy = if (sideLayout) height / 2f else dp(84) + diameter / 2f
        val imageSize = (diameter * .34f).roundToInt()
        fun measure(view: View, w: Int, h: Int) = view.measure(MeasureSpec.makeMeasureSpec(max(0, w), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(max(0, h), MeasureSpec.EXACTLY))
        measure(ring, width, height)
        pictures.values.forEach { measure(it, imageSize, imageSize) }
        val labelFraction = min(.36f, 1.6f / controller.destinations.size.coerceAtLeast(1))
        labels.forEach { it.textSize = if (sideLayout) 11f else 12f; measure(it, (diameter * labelFraction).roundToInt(), min(dp(52), (diameter * .19f).roundToInt())) }
        val closed = min(dp(250), reservePaneWidth - dp(32))
        val expanded = min(dp(500), reservePaneWidth - dp(24))
        measure(reserve, (closed + (expanded - closed) * reserveProgress).roundToInt(), dp(68))
        measure(empty, (diameter * .52f).roundToInt(), (diameter * .52f).roundToInt())
        measure(hint, reservePaneWidth - dp(20), dp(if (sideLayout) 110 else 50))
        activeGhost?.let { measure(it, it.layoutParams.width, it.layoutParams.height) }
        reorderGhost?.let { measure(it, dp(110), dp(44)) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        ring.layout(0, 0, width, height)
        reserve.layout((reservePaneWidth - reserve.measuredWidth) / 2, dp(8), (reservePaneWidth + reserve.measuredWidth) / 2, dp(76))
        val queue = controller.media.queue.take(3)
        pictures.forEach { (key, view) ->
            val depth = queue.indexOfFirst { it.uri.toString() == key }.coerceAtLeast(0)
            val x = (cx - view.measuredWidth / 2 + dp(depth * 5)).roundToInt()
            val y = (cy - view.measuredHeight / 2 - dp(depth * 6)).roundToInt()
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
            view.rotation = depth * -4f
        }
        labels.forEachIndexed { index, view ->
            if (view.visibility == GONE) return@forEachIndexed
            val angle = angle(index)
            val nudge = ring.amount[index] * dp(6)
            val radius = diameter * .385f + nudge
            val x = (cx + cos(angle) * radius - view.measuredWidth / 2f).roundToInt()
            val y = (cy + sin(angle) * radius - view.measuredHeight / 2f).roundToInt()
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        }
        empty.layout((cx - empty.measuredWidth / 2).toInt(), (cy - empty.measuredHeight / 2).toInt(),
            (cx + empty.measuredWidth / 2).toInt(), (cy + empty.measuredHeight / 2).toInt())
        if (sideLayout) hint.layout(dp(10), dp(84), reservePaneWidth - dp(10), dp(194))
        else hint.layout(dp(10), (cy + diameter / 2 + dp(4)).toInt(), width - dp(10), (cy + diameter / 2 + dp(54)).toInt())
        // Ghosts use x/y properties, so relayout cannot interrupt their flight.
        activeGhost?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
        reorderGhost?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
    }

    private fun morphReserve(open: Boolean) {
        controller.reserveOpen = open
        reserveAnimator?.cancel()
        compact.visibility = if (open) GONE else VISIBLE
        carousel.visibility = if (open) VISIBLE else GONE
        reserveAnimator = ValueAnimator.ofFloat(reserveProgress, if (open) 1f else 0f).apply {
            duration = 220; interpolator = DecelerateInterpolator()
            addUpdateListener { reserveProgress = it.animatedValue as Float; requestLayout() }
            start()
        }
    }

    private fun point(event: MotionEvent): Pair<Float, Float> {
        getLocationOnScreen(location)
        return event.rawX - location[0] to event.rawY - location[1]
    }

    @SuppressLint("ClickableViewAccessibility") // Every tap calls performClick; click menus expose all gesture actions.
    private fun installMediaGesture(view: ImageView, item: MediaItem, fromReserve: Boolean) {
        var downX = 0f; var downY = 0f; var start = 0L; var dragging = false; var scrolling = false
        var ghost: ImageView? = null
        view.setOnTouchListener { _, event ->
            if (!view.isEnabled) return@setOnTouchListener false
            val (x, y) = point(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x; downY = y; start = event.eventTime; dragging = false; scrolling = false
                    this@OrganizerBoard.parent?.requestDisallowInterceptTouchEvent(true)
                    if (!fromReserve || selectedReserved == item.uri.toString()) view.parent?.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = x - downX; val dy = y - downY
                    if (!dragging && !scrolling && hypot(dx, dy) > touchSlop) {
                        scrolling = fromReserve && selectedReserved != item.uri.toString() && abs(dx) > abs(dy) * 1.2f
                        if (scrolling) { scroll.requestDisallowInterceptTouchEvent(false) }
                        else {
                            dragging = true; view.parent?.requestDisallowInterceptTouchEvent(true)
                            scroll.requestDisallowInterceptTouchEvent(true)
                            ghost = ghost(view.drawable, view.width, view.height, x, y)
                            view.alpha = 0f
                        }
                    }
                    if (dragging) {
                        ghost?.apply { this.x = x - width / 2; this.y = y - height / 2 }
                        setHover(targetAt(x, y))
                    }
                    !scrolling
                }
                MotionEvent.ACTION_UP -> {
                    view.alpha = 1f
                    this@OrganizerBoard.parent?.requestDisallowInterceptTouchEvent(false)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    val dx = x - downX; val dy = y - downY
                    val velocity = hypot(dx, dy) / max(1L, event.eventTime - start)
                    val inReserve = !fromReserve && x >= reserve.left && x <= reserve.right && y >= reserve.top && y <= reserve.bottom
                    val target = if (fromReserve) targetAt(x, y).takeIf { it >= 0 }
                        else OrganizerRules.releasedTarget(dx, dy, diameter, velocity / resources.displayMetrics.density, controller.destinations.size)
                    if (dragging && inReserve) {
                        val fly = ghost ?: ghost(view.drawable, view.width, view.height, x, y)
                        controller.reserve(); flyTo(fly, reserve.x + reserve.width / 2, reserve.y + reserve.height / 2)
                    } else if (dragging && target != null) launch(item, fromReserve, target, ghost)
                    else { ghost?.let(::removeGhost); if (!dragging && !scrolling) view.performClick() }
                    ghost = null; dragging = false; setHover(-1); true
                }
                MotionEvent.ACTION_CANCEL -> { view.parent?.requestDisallowInterceptTouchEvent(false); view.alpha = 1f; ghost?.let(::removeGhost); ghost = null; dragging = false; setHover(-1); true }
                else -> true
            }
        }
        if (!fromReserve) {
            view.isClickable = true; view.isFocusable = true
            view.setOnClickListener {
                val names = controller.destinations.mapIndexed { i, album -> "${i + 1}. ${album.name}" } + "Reservar"
                Ui.showPopupOptions(view, names) { selected ->
                    val index = names.indexOf(selected)
                    if (index == controller.destinations.size) controller.reserve() else launch(item, false, index, null)
                }
            }
        }
    }

    private fun launch(item: MediaItem, fromReserve: Boolean, target: Int, existing: ImageView?) {
        val origin = pictures[item.uri.toString()] ?: scroll.findViewWithTag<ImageView>("organizer_reserved_${item.uri}")
        val originPoint = IntArray(2)
        origin?.getLocationOnScreen(originPoint); getLocationOnScreen(location)
        val fly = existing ?: ghost(origin?.drawable,
            origin?.width ?: (diameter * .34f).toInt(), origin?.height ?: (diameter * .34f).toInt(),
            if (origin != null) originPoint[0] - location[0] + origin.width / 2f else cx,
            if (origin != null) originPoint[1] - location[1] + origin.height / 2f else cy)
        if (!controller.send(item, fromReserve, target)) { removeGhost(fly); return }
        setHover(target); ring.receive(target)
        val angle = angle(target)
        flyTo(fly, cx + cos(angle) * diameter * .40f, cy + sin(angle) * diameter * .40f)
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    private fun ghost(drawable: Drawable?, w: Int, h: Int, x: Float, y: Float): ImageView = ImageView(context).apply {
        setImageDrawable(drawable); scaleType = ImageView.ScaleType.FIT_CENTER
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        cameraDistance = dp(700).toFloat()
        addView(this, LayoutParams(w, h)); measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        layout(0, 0, w, h); this.x = x - w / 2; this.y = y - h / 2
        activeGhost = this; bringToFront()
    }

    private fun flyTo(view: ImageView, x: Float, y: Float) {
        view.animate().x(x - view.width / 2).y(y - view.height / 2)
            .rotationX(if (y < cy) 56f else -56f).rotationY(if (x < cx) -56f else 56f)
            .scaleX(.14f).scaleY(.14f).setDuration(260).setInterpolator(DecelerateInterpolator())
            .withEndAction { removeGhost(view) }.start()
    }

    private fun removeGhost(view: ImageView) { removeView(view); if (activeGhost === view) activeGhost = null }

    private fun targetAt(x: Float, y: Float): Int {
        val distance = hypot(x - cx, y - cy)
        val count = controller.destinations.size
        return if (count > 0 && distance in diameter * .275f..diameter * .56f) OrganizerRules.sector(x - cx, y - cy, count) else -1
    }

    private fun setHover(index: Int) {
        if (hover == index) return
        hover = index; ring.hover(index)
    }

    private fun installReordering(label: View, index: Int) {
        var startX = 0f; var startY = 0f; var holding = false; var cancelled = false
        var activeIndex = index
        val hold = Runnable {
            if (!cancelled) {
                holding = true; performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                reorderGhost = Ui.label(context, controller.destinations.getOrNull(activeIndex)?.name.orEmpty()).apply {
                    setTextColor(OrganizerColors.highlightText(context)); background = Ui.rounded(OrganizerColors.highlight(context), 10, context)
                    addView(this, LayoutParams(dp(110), dp(44))); this.x = startX - dp(55); this.y = startY - dp(22)
                }
                setHover(activeIndex)
            }
        }
        label.setOnTouchListener { _, event ->
            val (x, y) = point(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    activeIndex = if (index < 0) targetAt(x, y) else index
                    if (activeIndex < 0) return@setOnTouchListener false
                    startX = x; startY = y; holding = false; cancelled = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                    postDelayed(hold, ViewConfiguration.getLongPressTimeout().toLong()); true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!holding && hypot(x - startX, y - startY) > touchSlop) { cancelled = true; removeCallbacks(hold) }
                    if (holding) { reorderGhost?.apply { this.x = x - dp(55); this.y = y - dp(22) }; setHover(targetAt(x, y)) }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(hold); parent?.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP) {
                        if (holding) controller.swap(activeIndex, targetAt(x, y))
                        else if (!cancelled) labels[activeIndex].performClick()
                    }
                    cancelled = true; holding = false; reorderGhost?.let(::removeView); reorderGhost = null; setHover(-1); true
                }
                else -> true
            }
        }
    }

    private fun chooseSwap(index: Int, anchor: View) {
        val options = controller.destinations.mapIndexed { i, album -> "${i + 1}. ${album.name}" }
        Ui.showPopupOptions(anchor, options) { controller.swap(index, options.indexOf(it)) }
    }

    private inner class ReserveAdapter : RecyclerView.Adapter<ReserveHolder>() {
        override fun getItemCount() = controller.media.reserved.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ReserveHolder(ImageView(context).apply {
            layoutParams = RecyclerView.LayoutParams(dp(72), -1).apply { marginEnd = dp(8) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = true; isFocusable = true
        })
        override fun onBindViewHolder(holder: ReserveHolder, position: Int) {
            val item = controller.media.reserved[position]
            holder.image.apply {
                tag = "organizer_reserved_${item.uri}"; contentDescription = "Mídia reservada ${position + 1}"
                load(item.uri) { size(160); crossfade(false) }
                isSelected = selectedReserved == item.uri.toString(); alpha = if (isSelected) .7f else 1f
                installMediaGesture(this, item, true)
                setOnClickListener {
                    val wasSelected = selectedReserved == item.uri.toString()
                    val previous = controller.media.reserved.indexOfFirst { it.uri.toString() == selectedReserved }
                    selectedReserved = item.uri.toString()
                    if (previous >= 0) notifyItemChanged(previous)
                    if (holder.bindingAdapterPosition >= 0) notifyItemChanged(holder.bindingAdapterPosition)
                    val names = controller.destinations.mapIndexed { i, album -> "${i + 1}. ${album.name}" }
                    if (wasSelected) Ui.showPopupOptions(this, names) { name -> launch(item, true, names.indexOf(name), null) }
                }
            }
        }
    }
    private class ReserveHolder(val image: ImageView) : RecyclerView.ViewHolder(image)

    /** Let vertical/diagonal pulls reach the thumbnail before RecyclerView claims
     * the horizontal component. A selected thumbnail already owns every axis. */
    private class ReserveRecyclerView(context: Context) : RecyclerView(context) {
        private var downX = 0f; private var downY = 0f
        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { downX = event.x; downY = event.y }
            if (event.actionMasked == MotionEvent.ACTION_MOVE && abs(event.x - downX) < abs(event.y - downY) * 1.2f) return false
            return super.onInterceptTouchEvent(event)
        }
    }

    private fun angle(index: Int) = Math.toRadians(-90.0 + index * 360.0 / controller.destinations.size.coerceAtLeast(1)).toFloat()
    private fun dp(value: Int) = Ui.dp(context, value)

    private inner class RingView(context: Context) : View(context) {
        val amount = FloatArray(OrganizerRules.MAX_DESTINATIONS)
        private val animators = arrayOfNulls<ValueAnimator>(OrganizerRules.MAX_DESTINATIONS)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        private val outer = RectF(); private val inner = RectF()
        private val receivedUntil = LongArray(OrganizerRules.MAX_DESTINATIONS)
        private var hovered = -1
        init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
        fun hover(index: Int) {
            hovered = index
            repeat(OrganizerRules.MAX_DESTINATIONS) { animateSector(it, if (it == index || android.os.SystemClock.uptimeMillis() < receivedUntil[it]) 1f else 0f) }
        }
        fun receive(index: Int) {
            receivedUntil[index] = android.os.SystemClock.uptimeMillis() + 280
            animateSector(index, 1f)
            postDelayed({ if (hovered != index && android.os.SystemClock.uptimeMillis() >= receivedUntil[index]) animateSector(index, 0f) }, 280)
        }
        private fun animateSector(index: Int, value: Float) {
            animators[index]?.cancel()
            animators[index] = ValueAnimator.ofFloat(amount[index], value).apply {
                duration = 140; interpolator = DecelerateInterpolator()
                addUpdateListener { amount[index] = it.animatedValue as Float; invalidate(); this@OrganizerBoard.requestLayout() }
                start()
            }
        }
        override fun onDraw(canvas: Canvas) {
            val count = controller.destinations.size
            if (count == 0) return
            val sweep = 360f / count
            repeat(count) { index ->
                val radius = diameter * .48f; val hole = diameter * .278f
                val nudge = amount[index] * dp(6)
                val x = cx + cos(angle(index)) * nudge; val y = cy + sin(angle(index)) * nudge
                outer.set(x - radius, y - radius, x + radius, y + radius)
                inner.set(x - hole, y - hole, x + hole, y + hole)
                path.reset()
                if (count == 1) {
                    path.fillType = Path.FillType.EVEN_ODD
                    path.addOval(outer, Path.Direction.CW)
                    path.addOval(inner, Path.Direction.CW)
                } else {
                    path.fillType = Path.FillType.WINDING
                    val start = -90f - sweep / 2 + .5f + index * sweep
                    path.arcTo(outer, start, sweep - 1f)
                    path.arcTo(inner, start + sweep - 1f, 1f - sweep); path.close()
                }
                val selected = controller.destinations.getOrNull(index)?.key == controller.lastAlbum
                paint.color = if (selected) OrganizerColors.highlight(context)
                    else Ui.blend(OrganizerColors.surface(context), Ui.bg(context), if (index % 2 == 0) .05f else .25f)
                canvas.drawPath(path, paint)
            }
        }
        override fun onDetachedFromWindow() { animators.forEach { it?.cancel() }; super.onDetachedFromWindow() }
    }
}

internal object OrganizerColors {
    fun surface(context: Context): Int {
        val normal = Ui.surface(context)
        return if (abs(Ui.luminance(normal) - Ui.luminance(Ui.bg(context))) < .025)
            Ui.blend(normal, Ui.text(context), .10f) else normal
    }
    fun highlight(context: Context): Int {
        val seed = Ui.themeSeed(context)
        return when (seed) {
            Color.BLACK -> Color.WHITE
            Color.WHITE -> Color.BLACK
            else -> if (Ui.darkMode(context)) Ui.blend(seed, Color.WHITE, .72f) else Ui.menuSurface(context)
        }
    }
    fun highlightText(context: Context) = if (Ui.luminance(highlight(context)) > .56) Color.BLACK else Color.WHITE
}

