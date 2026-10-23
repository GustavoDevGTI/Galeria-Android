package com.galeria.android

import android.animation.ValueAnimator
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.annotation.SuppressLint
import android.content.Context
import android.view.DragEvent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import coil3.load
import coil3.asImage
import coil3.request.allowHardware
import coil3.request.crossfade
import kotlin.math.*

/** Native view: preloaded image views are promoted, never replaced by a loading placeholder. */
@SuppressLint("ViewConstructor") // Programmatic screen; a retained session is required.
internal class OrganizerBoard(context: Context, private val controller: OrganizerController, private val createFolder: () -> Unit = {}) : FrameLayout(context) {
    private val ring = RingView(context)
    private val pictures = LinkedHashMap<String, ImageView>()
    private val labels = ArrayList<LinearLayout>()
    private val albumNames = ArrayList<TextView>()
    private val albumCounts = ArrayList<TextView>()
    private val reserve = FrameLayout(context)
    private val compact = LinearLayout(context)
    private val reservePreview = ImageView(context)
    private val reserveLabel = Ui.label(context, "")
    private val carousel = LinearLayout(context)
    private val scroll = ReserveRecyclerView(context)
    private val reserveAdapter = ReserveAdapter()
    private val reserveEmpty = Ui.label(context, "Arraste uma mídia para cá")
    private val empty = Ui.label(context, context.getString(R.string.organizer_empty))
    private val undo = LinearLayout(context)
    private var drawerProgress = if (controller.drawerOpen) 1f else 0f
    private var drawerAnimator: ValueAnimator? = null
    private val drawer = OrganizerAlbumDrawer(context, controller, createFolder, ::morphDrawer)
    private val fog = View(context)
    private var preview: FrameLayout? = null
    private var previewSource: ImageView? = null
    private var previewAnimator: ValueAnimator? = null
    private var previewProgress = 0f
    private val flights = LinkedHashMap<ImageView, ValueAnimator>()
    private val motion = PathInterpolator(.20f, 0f, .15f, 1f)
    private var reserveIds = emptyList<String>()
    private var selectedReserved: String? = null
    private var reserveProgress = if (controller.reserveOpen) 1f else 0f
    private var reserveAnimator: ValueAnimator? = null
    private var diameter = 0f
    private var cx = 0f
    private var cy = 0f
    private var sideLayout = false
    private var reservePaneWidth = 0
    private var reserveTop = 0
    private var reserveHeight = 0
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
            val label = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                tag = "organizer_destination_$index"
                isClickable = true; isFocusable = true
                setOnClickListener {
                    val album = controller.destinations.getOrNull(index) ?: return@setOnClickListener
                    if (OrganizerController.isEmpty(album)) {
                        val available = controller.availableAlbums
                        val names = available.mapIndexed { i, value -> "${i + 1}. ${value.name}" }
                        Ui.showPopupOptions(this, names) { name -> available.getOrNull(names.indexOf(name))?.let { controller.place(it, index) } }
                    } else {
                        val send = context.getString(R.string.organizer_send_to, album.name)
                        val options = if (index == OrganizerController.TRASH_SLOT) listOf(send) else listOf(send, "Remover da roda")
                        Ui.showPopupOptions(this, options) {
                            if (it == send) controller.media.queue.firstOrNull()?.let { item -> launch(item, false, index, null) }
                            else controller.removeDestination(index)
                        }
                    }
                }
                installReordering(this, index)
            }
            albumNames += Ui.label(context, "").apply {
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; textSize = 12f
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                label.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
            albumCounts += Ui.title(context, "0", 15).apply {
                tag = "organizer_destination_count_$index"
                gravity = Gravity.CENTER; alpha = .8f
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                label.addView(this, LinearLayout.LayoutParams(-1, -2))
            }
            labels.add(label); addView(label)
        }
        reserve.apply {
            tag = "organizer_reserve"; background = Ui.rounded(OrganizerColors.surface(context), 14, context)
            contentDescription = context.getString(R.string.organizer_reserve, 0)
        }
        compact.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL; isClickable = true; isFocusable = true
            tag = "organizer_reserve_button"; setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { morphReserve(true) }
        }
        reservePreview.scaleType = ImageView.ScaleType.CENTER_CROP
        reservePreview.setImageResource(R.drawable.ic_folder); reservePreview.setColorFilter(Ui.text(context))
        reservePreview.setPadding(dp(14), dp(14), dp(14), dp(14))
        compact.setPadding(dp(6), dp(6), dp(6), dp(4))
        reservePreview.background = Ui.rounded(Color.TRANSPARENT, 10, context)
        reservePreview.clipToOutline = true
        compact.addView(reservePreview, LinearLayout.LayoutParams(-1, 0, 1f))
        reserveLabel.textSize = 12f
        compact.addView(reserveLabel, LinearLayout.LayoutParams(-1, dp(24)))
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
        undo.apply {
            tag = "organizer_undo"; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
            background = Ui.rounded(OrganizerColors.surface(context), 24, context)
            contentDescription = context.getString(R.string.organizer_undo)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_undo); setColorFilter(Ui.text(context))
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(Ui.label(context, context.getString(R.string.organizer_undo)).apply {
                setTextColor(Ui.text(context)); setPadding(dp(10), 0, 0, 0)
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            })
            setOnClickListener { controller.undo() }
        }
        addView(undo)
        addView(drawer)
        installAlbumDrops()
        fog.apply {
            tag = "organizer_preview_fog"; setBackgroundColor(Ui.bg(context)); visibility = GONE
            elevation = dp(32).toFloat()
            contentDescription = context.getString(R.string.organizer_close_preview)
            isClickable = true; setOnClickListener { dismissPreview() }
        }
        addView(fog)
        render()
    }

    fun render() {
        val previous = topId
        val next = controller.media.queue.take(12)
        if (preview != null && previous != next.firstOrNull()?.uri?.toString()) clearPreview()
        val keep = next.map { it.uri.toString() }.toSet()
        pictures.keys.filter { it !in keep }.toList().forEach { key -> removeView(pictures.remove(key)) }
        next.forEach { item ->
            val key = item.uri.toString()
            pictures.getOrPut(key) {
                ImageView(context).apply {
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = context.getString(R.string.organizer_preview)
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
                scaleX = .965f; scaleY = .965f
                animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(motion).start()
            }
        }
        empty.visibility = if (next.isEmpty()) VISIBLE else GONE
        drawer.render()
        labels.forEachIndexed { index, label ->
            val album = controller.destinations.getOrNull(index)
            label.visibility = if (album == null) GONE else VISIBLE
            val count = album?.let { controller.sentCount(it.key) } ?: 0
            albumNames[index].text = album?.name.orEmpty()
            albumCounts[index].text = count.toString()
            albumCounts[index].visibility = if (album != null && OrganizerController.isEmpty(album)) GONE else VISIBLE
            label.contentDescription = album?.let { context.getString(R.string.organizer_send_to, it.name) + ", " +
                resources.getQuantityString(R.plurals.organizer_sent, count, count) }
            val color = if (album?.key == controller.lastAlbum) OrganizerColors.highlightText(context) else Ui.text(context)
            albumNames[index].setTextColor(color); albumCounts[index].setTextColor(color)
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
                reservePreview.load(recent.uri) { size(320); crossfade(false); ImageRotation.configureRequest(context, recent, this) }
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
        updateReserveAppearance()
        scroll.visibility = if (ids.isEmpty()) GONE else VISIBLE
        reserveEmpty.visibility = if (ids.isEmpty()) VISIBLE else GONE
        undo.isEnabled = controller.canUndo; undo.alpha = if (undo.isEnabled) 1f else .4f
        reserve.bringToFront(); undo.bringToFront(); drawer.bringToFront()
        flights.keys.forEach { it.bringToFront() }; activeGhost?.bringToFront(); reorderGhost?.bringToFront()
        if (preview != null) { fog.bringToFront(); preview?.bringToFront() }
        ring.invalidate(); requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val available = MeasureSpec.getSize(heightMeasureSpec)
        val desired = min(width, dp(620)) + dp(230)
        val height = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) available else max(available, desired)
        setMeasuredDimension(width, height)
        sideLayout = width > height
        reservePaneWidth = if (sideLayout) min(dp(280), (width * .32f).roundToInt()) else width
        reserveHeight = (if (sideLayout) height * .6f - dp(56) else height * .15f).roundToInt().coerceIn(dp(64), dp(104))
        reserveTop = dp(4)
        val expandedDrawer = if (sideLayout) min(dp(144), max(dp(100), (height * .4f).roundToInt()))
            else max(dp(120), height - (reserveHeight + dp(8)) - min(width - dp(16), dp(620)) - dp(16))
        val drawerHeight = (dp(44) + (expandedDrawer - dp(44)) * drawerProgress).roundToInt()
        val controlsHeight = reserveHeight + dp(8)
        diameter = if (sideLayout) min(width - reservePaneWidth - dp(16), height - drawerHeight - dp(12)).coerceAtLeast(dp(64)).toFloat()
            else min(min(width - dp(16), height - controlsHeight - drawerHeight - dp(16)), dp(620)).coerceAtLeast(dp(64)).toFloat()
        cx = if (sideLayout) reservePaneWidth + (width - reservePaneWidth) / 2f else width / 2f
        cy = if (sideLayout) (height - drawerHeight) / 2f else controlsHeight + dp(4) + diameter / 2f
        val imageSize = (diameter * .43f).roundToInt()
        fun measure(view: View, w: Int, h: Int) = view.measure(MeasureSpec.makeMeasureSpec(max(0, w), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(max(0, h), MeasureSpec.EXACTLY))
        measure(ring, width, height)
        pictures.values.forEach { measure(it, imageSize, imageSize) }
        val labelFraction = min(.36f, 1.6f / controller.destinations.size.coerceAtLeast(1))
        albumNames.forEach { it.textSize = if (sideLayout) 11f else 12f }
        albumCounts.forEach { it.textSize = if (sideLayout) 12f else 15f }
        labels.forEach { measure(it, max(dp(44), (diameter * labelFraction).roundToInt()), dp(if (sideLayout) 36 else 44)) }
        val closed = min((reserveHeight * .9f).roundToInt(), reservePaneWidth - dp(24))
        val expanded = min(dp(600), reservePaneWidth - dp(24))
        measure(reserve, (closed + (expanded - closed) * reserveProgress).roundToInt(), reserveHeight)
        measure(empty, (diameter * .52f).roundToInt(), (diameter * .52f).roundToInt())
        measure(undo, dp(108), dp(48))
        measure(drawer, width - dp(16), drawerHeight)
        measure(fog, width, height)
        preview?.let { val size = min(width - dp(56), height - dp(96)).coerceAtLeast(dp(48)); measure(it, size, size) }
        activeGhost?.let { measure(it, it.layoutParams.width, it.layoutParams.height) }
        reorderGhost?.let { measure(it, dp(110), dp(44)) }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        ring.layout(0, 0, width, height)
        reserve.layout((reservePaneWidth - reserve.measuredWidth) / 2, reserveTop,
            (reservePaneWidth + reserve.measuredWidth) / 2, reserveTop + reserveHeight)
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
            val nudge = ring.amount[index] * dp(8)
            val radius = diameter * .385f + nudge
            val x = (cx + cos(angle) * radius - view.measuredWidth / 2f).roundToInt()
            val y = (cy + sin(angle) * radius - view.measuredHeight / 2f).roundToInt()
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        }
        empty.layout((cx - empty.measuredWidth / 2).toInt(), (cy - empty.measuredHeight / 2).toInt(),
            (cx + empty.measuredWidth / 2).toInt(), (cy + empty.measuredHeight / 2).toInt())
        val undoLeft = if (sideLayout) (reservePaneWidth - dp(108)) / 2 else min(width - dp(112), reserve.right + dp(6))
        val undoTop = if (sideLayout) reserve.bottom + dp(4) else reserveTop + (reserveHeight - dp(48)) / 2
        undo.layout(undoLeft, undoTop, undoLeft + undo.measuredWidth, undoTop + undo.measuredHeight)
        drawer.layout(dp(8), height - drawer.measuredHeight, width - dp(8), height)
        fog.layout(0, 0, width, height)
        preview?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight); applyPreviewTransform() }
        // Ghosts use x/y properties, so relayout cannot interrupt their flight.
        activeGhost?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
        reorderGhost?.let { it.layout(0, 0, it.measuredWidth, it.measuredHeight) }
    }

    private fun morphReserve(open: Boolean) {
        controller.reserveOpen = open
        reserveAnimator?.cancel()
        reserveAnimator = ValueAnimator.ofFloat(reserveProgress, if (open) 1f else 0f).apply {
            duration = if (motionEnabled()) 320 else 0; interpolator = motion
            addUpdateListener { reserveProgress = it.animatedValue as Float; updateReserveAppearance(); requestLayout() }
            start()
        }
    }

    private fun updateReserveAppearance() {
        compact.visibility = if (reserveProgress >= 1f) GONE else VISIBLE
        carousel.visibility = if (reserveProgress <= 0f) GONE else VISIBLE
        compact.alpha = 1f - reserveProgress
        carousel.alpha = reserveProgress
        carousel.translationX = dp(10) * (1f - reserveProgress)
        undo.visibility = if (reserveProgress > .01f) GONE else VISIBLE
    }

    private fun showPreview(source: ImageView) {
        if (preview != null || source.drawable == null) return
        previewSource = source
        source.animate().cancel(); source.alpha = 0f
        previewProgress = 0f
        preview = FrameLayout(context).apply {
            val photo = ImageView(context).apply { setImageDrawable(source.drawable); scaleType = ImageView.ScaleType.FIT_CENTER }
            addView(photo, LayoutParams(-1, -1))
            tag = "organizer_preview"
            cameraDistance = dp(1600).toFloat()
            isClickable = true; isFocusable = true
            contentDescription = context.getString(R.string.organizer_close_preview)
            setOnClickListener { dismissPreview() }
            this@OrganizerBoard.addView(this); requestFocus()
            controller.media.queue.firstOrNull()?.let { item ->
                if (!item.isVideo()) photo.load(item.uri) {
                    size(min(1600, max(this@OrganizerBoard.width, this@OrganizerBoard.height)))
                    placeholder(source.drawable.asImage()); crossfade(120)
                    ImageRotation.configureRequest(context, item, this)
                }
                if (item.isVideo()) addView(OrganizerVideoPreview(context, item), LayoutParams(-1, -1))
            }
        }
        fog.visibility = VISIBLE; fog.alpha = 0f; fog.bringToFront(); preview?.bringToFront()
        blurBackground(true)
        requestLayout()
        animatePreview(1f)
    }

    fun dismissPreviewImmediately() { clearPreview() }
    fun dismissPreview(): Boolean {
        if (preview == null) return false
        animatePreview(0f)
        return true
    }

    private fun animatePreview(target: Float) {
        previewAnimator?.cancel()
        val image = preview ?: return
        previewAnimator = ValueAnimator.ofFloat(previewProgress, target).apply {
            duration = if (motionEnabled()) (if (target == 1f) 340L else 260L) else 0L
            interpolator = motion
            addUpdateListener { previewProgress = it.animatedValue as Float; applyPreviewTransform() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (preview === image && previewProgress == 0f) clearPreview()
                }
            })
            start()
        }
    }

    private fun applyPreviewTransform() {
        val image = preview ?: return
        val source = previewSource ?: return
        if (image.width == 0) return
        val t = previewProgress
        val startScale = source.width.toFloat() / image.width
        val scale = startScale + (1f - startScale) * t
        image.scaleX = scale; image.scaleY = scale
        image.x = source.x + source.width / 2f + (width / 2f - source.x - source.width / 2f) * t - image.width / 2f
        image.y = source.y + source.height / 2f + (height / 2f - source.y - source.height / 2f) * t - image.height / 2f
        image.rotationX = 0f; image.rotationY = 0f; image.rotation = 0f
        image.elevation = dp(40) + dp(24) * t
        fog.alpha = .48f * t
    }

    private fun blurBackground(enabled: Boolean) {
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            val effect = if (enabled) RenderEffect.createBlurEffect(dp(10).toFloat(), dp(10).toFloat(), Shader.TileMode.CLAMP) else null
            (listOf(ring, reserve, undo, drawer, empty) + labels + pictures.values).forEach { it.setRenderEffect(effect) }
        }
    }

    private fun clearPreview() {
        val image = preview ?: return
        preview = null; previewAnimator?.cancel(); previewAnimator = null
        removeView(image); previewSource?.alpha = 1f; previewSource = null
        fog.visibility = GONE; previewProgress = 0f; blurBackground(false)
    }

    private fun motionEnabled() = android.os.Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()

    private fun point(event: MotionEvent): Pair<Float, Float> {
        getLocationOnScreen(location)
        return event.rawX - location[0] to event.rawY - location[1]
    }

    @SuppressLint("ClickableViewAccessibility") // Every tap calls performClick; click menus expose all gesture actions.
    private fun installMediaGesture(view: ImageView, item: MediaItem, fromReserve: Boolean) {
        var downX = 0f; var downY = 0f; var start = 0L; var dragging = false; var scrolling = false
        var originX = 0f; var originY = 0f; var gripX = 0f; var gripY = 0f
        var ghost: ImageView? = null
        view.setOnTouchListener { _, event ->
            if (!view.isEnabled) return@setOnTouchListener false
            val (x, y) = point(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = x; downY = y; start = event.eventTime; dragging = false; scrolling = false
                    val origin = IntArray(2); view.getLocationOnScreen(origin)
                    originX = origin[0] - location[0].toFloat(); originY = origin[1] - location[1].toFloat()
                    gripX = x - originX; gripY = y - originY
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
                            ghost = ghost(view.drawable, view.width, view.height, originX + view.width / 2f, originY + view.height / 2f)
                            view.alpha = 0f
                        }
                    }
                    if (dragging) {
                        ghost?.apply {
                            this.x = x - gripX; this.y = y - gripY
                            rotationY = (dx / diameter * 16f).coerceIn(-9f, 9f)
                            rotationX = (-dy / diameter * 12f).coerceIn(-7f, 7f)
                            scaleX = 1.025f; scaleY = 1.025f; elevation = dp(12).toFloat()
                        }
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
                    else {
                        ghost?.let { view.alpha = 0f; returnToOrigin(it, view, originX, originY) }
                        if (!dragging && !scrolling) view.performClick()
                    }
                    ghost = null; dragging = false; setHover(-1); true
                }
                MotionEvent.ACTION_CANCEL -> {
                    this@OrganizerBoard.parent?.requestDisallowInterceptTouchEvent(false)
                    view.parent?.requestDisallowInterceptTouchEvent(false)
                    ghost?.let { returnToOrigin(it, view, originX, originY) } ?: run { view.alpha = 1f }
                    ghost = null; dragging = false; setHover(-1); true
                }
                else -> true
            }
        }
        if (!fromReserve) {
            view.isClickable = true; view.isFocusable = true
            view.setOnClickListener { showPreview(view) }
        }
    }

    private fun launch(item: MediaItem, fromReserve: Boolean, target: Int, existing: ImageView?) {
        val origin = pictures[item.uri.toString()] ?: scroll.findViewWithTag<ImageView>("organizer_reserved_${item.uri}")
        val originPoint = IntArray(2)
        origin?.getLocationOnScreen(originPoint); getLocationOnScreen(location)
        val fly = existing ?: ghost(origin?.drawable,
            origin?.width ?: (diameter * .43f).toInt(), origin?.height ?: (diameter * .43f).toInt(),
            if (origin != null) originPoint[0] - location[0] + origin.width / 2f else cx,
            if (origin != null) originPoint[1] - location[1] + origin.height / 2f else cy)
        if (!controller.send(item, fromReserve, target)) { removeGhost(fly); return }
        setHover(target); ring.receive(target)
        val angle = angle(target)
        flyTo(fly, cx + cos(angle) * diameter * .40f, cy + sin(angle) * diameter * .40f) { ring.receive(target) }
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

    private fun flyTo(view: ImageView, x: Float, y: Float, arrived: (() -> Unit)? = null) {
        val startX = view.x + view.width / 2f; val startY = view.y + view.height / 2f
        val dx = x - startX; val dy = y - startY
        val controlX = (startX + x) / 2f + dy * .12f
        val controlY = (startY + y) / 2f - dx * .12f - dp(12)
        val initialScale = view.scaleX; val initialX = view.rotationX; val initialY = view.rotationY
        animateGhost(view, if (motionEnabled()) 340L else 0L, { t ->
            val u = 1f - t
            view.x = u * u * startX + 2f * u * t * controlX + t * t * x - view.width / 2f
            view.y = u * u * startY + 2f * u * t * controlY + t * t * y - view.height / 2f
            view.scaleX = initialScale + (.18f - initialScale) * t; view.scaleY = view.scaleX
            view.rotationX = initialX + ((if (dy < 0) 22f else -22f) - initialX) * t
            view.rotationY = initialY + ((if (dx < 0) -22f else 22f) - initialY) * t
            view.alpha = (1f - ((t - .65f) / .35f).coerceIn(0f, 1f))
        }, arrived)
    }

    private fun returnToOrigin(ghost: ImageView, source: ImageView, x: Float, y: Float) {
        val sx = ghost.x; val sy = ghost.y; val rx = ghost.rotationX; val ry = ghost.rotationY
        animateGhost(ghost, if (motionEnabled()) 240L else 0L, { t ->
            ghost.x = sx + (x - sx) * t; ghost.y = sy + (y - sy) * t
            ghost.rotationX = rx * (1f - t); ghost.rotationY = ry * (1f - t)
            ghost.scaleX = 1f + .025f * (1f - t); ghost.scaleY = ghost.scaleX
        }) { source.alpha = 1f }
    }

    private fun animateGhost(view: ImageView, durationMs: Long, frame: (Float) -> Unit, completed: (() -> Unit)?) {
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs; interpolator = motion
            addUpdateListener { frame(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    flights.remove(view); removeGhost(view)
                    if (!cancelled) completed?.invoke()
                }
            })
        }
        flights[view] = animator; animator.start()
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
        // Labels have a large accessible touch target; the ring itself stays passive.
        if (index < 0) return
        label.setOnLongClickListener {
            val album = controller.destinations.getOrNull(index)
            if (controller.saving || index == OrganizerController.TRASH_SLOT || album == null || OrganizerController.isEmpty(album)) false
            else label.startOrganizerAlbumDrag(OrganizerAlbumDrag(slot = index))
        }
    }

    private fun installAlbumDrops() {
        setOnDragListener { _, event ->
            val drag = event.localState as? OrganizerAlbumDrag ?: return@setOnDragListener false
            when (event.action) {
                DragEvent.ACTION_DRAG_STARTED -> !controller.saving
                DragEvent.ACTION_DRAG_LOCATION -> { setHover(targetAt(event.x, event.y).takeUnless { it == OrganizerController.TRASH_SLOT } ?: -1); true }
                DragEvent.ACTION_DROP -> {
                    val target = targetAt(event.x, event.y)
                    if (event.y >= drawer.top && drag.slot >= 0) controller.removeDestination(drag.slot)
                    else if (target >= 0 && target != OrganizerController.TRASH_SLOT) {
                        drag.album?.let { controller.place(it, target) } ?: controller.swap(drag.slot, target)
                    }
                    setHover(-1); true
                }
                DragEvent.ACTION_DRAG_ENDED -> { setHover(-1); true }
                else -> true
            }
        }
    }

    private fun morphDrawer(open: Boolean) {
        controller.drawerOpen = open; drawer.render(); drawerAnimator?.cancel()
        drawerAnimator = ValueAnimator.ofFloat(drawerProgress, if (open) 1f else 0f).apply {
            duration = if (motionEnabled()) 280 else 0; interpolator = motion
            addUpdateListener { drawerProgress = it.animatedValue as Float; requestLayout() }; start()
        }
    }
    private fun chooseSwap(index: Int, anchor: View) {
        val options = controller.destinations.mapIndexed { i, album -> "${i + 1}. ${album.name}" }
        Ui.showPopupOptions(anchor, options) { controller.swap(index, options.indexOf(it)) }
    }

    private inner class ReserveAdapter : RecyclerView.Adapter<ReserveHolder>() {
        override fun getItemCount() = controller.media.reserved.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ReserveHolder(ImageView(context).apply {
            layoutParams = RecyclerView.LayoutParams(dp(100), -1).apply { marginEnd = dp(8) }
            scaleType = ImageView.ScaleType.CENTER_CROP
            isClickable = true; isFocusable = true
        })
        override fun onBindViewHolder(holder: ReserveHolder, position: Int) {
            val item = controller.media.reserved[position]
            holder.image.apply {
                tag = "organizer_reserved_${item.uri}"; contentDescription = "Mídia reservada ${position + 1}"
                load(item.uri) { size(320); crossfade(false); ImageRotation.configureRequest(context, item, this) }
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

    override fun onDetachedFromWindow() {
        clearPreview(); reserveAnimator?.cancel(); drawerAnimator?.cancel()
        flights.values.toList().forEach { it.cancel() }
        activeGhost?.let(::removeGhost)
        pictures.values.forEach { it.animate().cancel() }
        super.onDetachedFromWindow()
    }

    private inner class RingView(context: Context) : View(context) {
        val amount = FloatArray(OrganizerRules.MAX_DESTINATIONS)
        private val animators = arrayOfNulls<ValueAnimator>(OrganizerRules.MAX_DESTINATIONS)
        private val targets = FloatArray(OrganizerRules.MAX_DESTINATIONS)
        private val settle = arrayOfNulls<Runnable>(OrganizerRules.MAX_DESTINATIONS)
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
            receivedUntil[index] = android.os.SystemClock.uptimeMillis() + 360
            animateSector(index, 1f)
            settle[index]?.let(::removeCallbacks)
            settle[index] = Runnable { if (hovered != index) animateSector(index, 0f) }.also { postDelayed(it, 360) }
        }
        private fun animateSector(index: Int, value: Float) {
            if (targets[index] == value && (animators[index]?.isRunning == true || amount[index] == value)) return
            targets[index] = value
            animators[index]?.cancel()
            animators[index] = ValueAnimator.ofFloat(amount[index], value).apply {
                duration = if (motionEnabled()) (if (value == 1f) 240L else 360L) else 0L
                interpolator = if (value == 1f) OvershootInterpolator(.45f) else motion
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
                val nudge = amount[index] * dp(8)
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
        override fun onDetachedFromWindow() {
            animators.forEach { it?.cancel() }; settle.forEach { it?.let(::removeCallbacks) }
            super.onDetachedFromWindow()
        }
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

