package com.galeria.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt

/** Rules for the strip: where each square sits and which slot a dragged square lands in. */
internal object JoinStripRules {
    fun slotX(index: Int, padding: Float, cell: Float, gap: Float): Float = padding + index * (cell + gap)

    /** The slot under the dragged square's centre, kept inside the list. */
    fun slotAt(centerX: Float, count: Int, padding: Float, cell: Float, gap: Float): Int =
        ((centerX - padding - cell / 2f) / (cell + gap)).roundToInt().coerceIn(0, maxOf(0, count - 1))

    /** Where square [index] shows while the square at [from] is held over slot [to]. */
    fun displayedSlot(index: Int, from: Int, to: Int): Int = when {
        index == from -> to
        from < to && index in (from + 1)..to -> index - 1
        from > to && index in to until from -> index + 1
        else -> index
    }
}

/**
 * The montage as a row of squares. Hold a square to lift it and drag it sideways; the others
 * slide to make room, and letting go puts it in the new place. A short drag scrolls the row.
 */
internal class JoinStripView(context: Context) : View(context) {
    var onSelect: ((Int) -> Unit)? = null
    var onMove: ((from: Int, to: Int) -> Unit)? = null
    /** The square after the last media, which adds more media to the montage. */
    var onAdd: (() -> Unit)? = null
    /** A junction circle was tapped: its index and its centre, in this view's coordinates. */
    var onJunction: ((index: Int, centerX: Float) -> Unit)? = null

    private var clips: List<JoinClip> = emptyList()
    private var labels: List<String> = emptyList()
    private var thumbs: Map<String, Bitmap> = emptyMap()
    private var selected = -1

    private val density = resources.displayMetrics.density
    private val cell = 56f * density
    // Room between squares for the junction circle that picks the transition.
    private val gap = 28f * density
    private val junctionRadius = 11f * density
    private var transitions: List<JoinTransition> = emptyList()
    private var activeJunction = -1
    private var downJunction = -1
    private val padding = 16f * density
    private val radius = 10f * density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val holdMs = 220L
    private val scroller = OverScroller(context)
    private var velocity: android.view.VelocityTracker? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF23272C.toInt() }
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Color.WHITE }
    private val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2.5f * density; strokeCap = Paint.Cap.ROUND }
    private val junctionFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val junctionMark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.6f * density; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 11f * density; textAlign = Paint.Align.RIGHT; setShadowLayer(3f * density, 0f, 0f, 0x99000000.toInt()) }
    private val clip = Path()
    private val rect = RectF()
    private val matrix = Matrix()

    // Drag state: the lifted square, where it is held and where the others are drawn.
    private var dragFrom = -1
    private var dragTarget = -1
    private var dragX = 0f
    private var lift = 0f
    private val shown = ArrayList<Float>()
    private var slide: ValueAnimator? = null
    private var liftAnimator: ValueAnimator? = null

    private var downX = 0f
    private var downScroll = 0
    private var downIndex = -1
    private var scrolling = false
    private val lifter = Runnable { pickUp() }

    init {
        isFocusable = true
        contentDescription = context.getString(R.string.join_strip)
    }

    fun setClips(clips: List<JoinClip>, labels: List<String>, selected: Int, transitions: List<JoinTransition> = emptyList()) {
        this.transitions = transitions
        this.clips = clips
        this.labels = labels
        this.selected = selected
        if (dragFrom < 0) resetShown()
        scrollTo(scrollX.coerceIn(0, maxScroll()), 0)
        invalidate()
    }

    fun setThumbs(thumbs: Map<String, Bitmap>) { this.thumbs = thumbs; invalidate() }

    fun setSelected(index: Int) {
        if (selected == index) return
        selected = index
        reveal(index)
        invalidate()
    }

    private fun resetShown() {
        shown.clear()
        clips.indices.forEach { shown += JoinStripRules.slotX(it, padding, cell, gap) }
    }

    private fun addSlot() = clips.size
    private fun contentWidth() = padding * 2 + (clips.size + 1) * cell + clips.size * gap
    private fun maxScroll() = maxOf(0, (contentWidth() - width).roundToInt())

    private fun reveal(index: Int) {
        if (index !in clips.indices || width == 0) return
        val left = JoinStripRules.slotX(index, padding, cell, gap) - padding
        val right = left + cell + padding * 2
        val target = when {
            left < scrollX -> left.roundToInt()
            right > scrollX + width -> (right - width).roundToInt()
            else -> return
        }.coerceIn(0, maxScroll())
        scroller.forceFinished(true)
        scroller.startScroll(scrollX, 0, target - scrollX, 0, Motion.MODAL_MS.toInt())
        postInvalidateOnAnimation()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (cell + 16f * density).roundToInt())
    }

    override fun onDraw(canvas: Canvas) {
        val top = (height - cell) / 2f
        clips.indices.forEach { if (it != dragFrom) drawSquare(canvas, it, shown.getOrElse(it) { 0f }, top, 1f) }
        if (dragFrom < 0) (0 until maxOf(0, clips.lastIndex)).forEach { drawJunction(canvas, it, top + cell / 2f) }
        drawAddSquare(canvas, JoinStripRules.slotX(addSlot(), padding, cell, gap), top)
        if (dragFrom in clips.indices) drawSquare(canvas, dragFrom, dragX, top, 1f + 0.08f * lift)
    }

    private fun drawSquare(canvas: Canvas, index: Int, x: Float, top: Float, scale: Float) {
        val media = clips[index].media
        canvas.save()
        canvas.scale(scale, scale, x + cell / 2f, top + cell / 2f)
        rect.set(x, top, x + cell, top + cell)
        clip.reset()
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawRect(rect, fill)
        thumbs[media.uri]?.let { bitmap ->
            val s = maxOf(cell / bitmap.width, cell / bitmap.height)
            matrix.setScale(s, s)
            matrix.postTranslate(x + (cell - bitmap.width * s) / 2f, top + (cell - bitmap.height * s) / 2f)
            canvas.drawBitmap(bitmap, matrix, image)
        }
        canvas.restore()
        labels.getOrNull(index)?.let { canvas.drawText(it, x + cell - 5f * density, top + cell - 6f * density, text) }
        if (index == selected) canvas.drawRoundRect(rect, radius, radius, ring)
        canvas.restore()
    }

    private fun drawAddSquare(canvas: Canvas, x: Float, top: Float) {
        rect.set(x, top, x + cell, top + cell)
        canvas.drawRoundRect(rect, radius, radius, fill)
        val c = cell / 2f
        val arm = cell * 0.18f
        canvas.drawLine(x + c - arm, top + c, x + c + arm, top + c, plus)
        canvas.drawLine(x + c, top + c - arm, x + c, top + c + arm, plus)
    }

    fun junctionCenter(index: Int): Float = JoinStripRules.slotX(index, padding, cell, gap) + cell + gap / 2f - scrollX

    fun setActiveJunction(index: Int) { activeJunction = index; invalidate() }

    private fun drawJunction(canvas: Canvas, index: Int, centerY: Float) {
        val x = JoinStripRules.slotX(index, padding, cell, gap) + cell + gap / 2f
        val chosen = transitions.getOrElse(index) { JoinTransition.NONE } != JoinTransition.NONE
        junctionFill.color = if (chosen) Color.WHITE else 0xFF23272C.toInt()
        canvas.drawCircle(x, centerY, junctionRadius, junctionFill)
        if (index == activeJunction) canvas.drawCircle(x, centerY, junctionRadius + 3f * density, ring)
        junctionMark.color = if (chosen) 0xFF101214.toInt() else 0xFFC9CED6.toInt()
        if (chosen) {
            // Two overlapping squares: one media blending into the next.
            val s = 4f * density
            rect.set(x - s * 1.4f, centerY - s * 1.4f, x + s * 0.6f, centerY + s * 0.6f)
            canvas.drawRoundRect(rect, s / 3f, s / 3f, junctionMark)
            rect.offset(s * 0.8f, s * 0.8f)
            canvas.drawRoundRect(rect, s / 3f, s / 3f, junctionMark)
        } else {
            canvas.drawLine(x, centerY - 5f * density, x, centerY + 5f * density, junctionMark)
        }
    }

    private fun junctionAt(x: Float): Int = (0 until maxOf(0, clips.lastIndex)).firstOrNull {
        abs(x + scrollX - (JoinStripRules.slotX(it, padding, cell, gap) + cell + gap / 2f)) <= gap / 2f
    } ?: -1

    private fun isAddAt(x: Float): Boolean {
        val left = JoinStripRules.slotX(addSlot(), padding, cell, gap)
        return x + scrollX in left..(left + cell)
    }

    private fun indexAt(x: Float): Int {
        val content = x + scrollX
        return clips.indices.firstOrNull {
            val left = JoinStripRules.slotX(it, padding, cell, gap)
            content in left..(left + cell)
        } ?: -1
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (velocity == null) velocity = android.view.VelocityTracker.obtain()
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                downX = event.x
                downScroll = scrollX
                downIndex = indexAt(event.x)
                downJunction = if (downIndex < 0) junctionAt(event.x) else -1
                scrolling = false
                if (downIndex >= 0) postDelayed(lifter, holdMs)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragFrom >= 0) {
                    dragX = event.x + scrollX - cell / 2f
                    autoScroll(event.x)
                    retarget()
                    invalidate()
                } else {
                    if (!scrolling && abs(event.x - downX) > touchSlop) {
                        scrolling = true
                        removeCallbacks(lifter)
                    }
                    if (scrolling) scrollTo((downScroll - (event.x - downX)).roundToInt().coerceIn(0, maxScroll()), 0)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(lifter)
                when {
                    dragFrom >= 0 -> drop()
                    scrolling -> {
                        velocity?.computeCurrentVelocity(1000)
                        scroller.fling(scrollX, 0, -(velocity?.xVelocity ?: 0f).toInt(), 0, 0, maxScroll(), 0, 0)
                        postInvalidateOnAnimation()
                    }
                    downIndex >= 0 -> { performClick(); onSelect?.invoke(downIndex) }
                    downJunction >= 0 -> { performClick(); onJunction?.invoke(downJunction, junctionCenter(downJunction)) }
                    isAddAt(event.x) -> { performClick(); onAdd?.invoke() }
                }
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(lifter)
                if (dragFrom >= 0) { dragTarget = dragFrom; drop() }
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX.coerceIn(0, maxScroll()), 0)
            postInvalidateOnAnimation()
        }
    }

    private fun pickUp() {
        if (downIndex !in clips.indices || scrolling) return
        dragFrom = downIndex
        dragTarget = downIndex
        dragX = JoinStripRules.slotX(downIndex, padding, cell, gap)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onSelect?.invoke(downIndex)
        animateLift(1f)
    }

    private fun animateLift(to: Float) {
        liftAnimator?.cancel()
        if (!Motion.movementEnabled()) { lift = to; invalidate(); return }
        liftAnimator = ValueAnimator.ofFloat(lift, to).apply {
            duration = Motion.PRESS_MS
            interpolator = Motion.easeOut
            addUpdateListener { lift = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun autoScroll(x: Float) {
        val edge = cell
        val step = when {
            x < edge -> -(edge - x) / 4f
            x > width - edge -> (x - (width - edge)) / 4f
            else -> 0f
        }
        if (step != 0f) {
            val before = scrollX
            scrollTo((scrollX + step).roundToInt().coerceIn(0, maxScroll()), 0)
            dragX += scrollX - before
        }
    }

    private fun retarget() {
        val target = JoinStripRules.slotAt(dragX + cell / 2f, clips.size, padding, cell, gap)
        if (target == dragTarget) return
        dragTarget = target
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        slideTo { index -> JoinStripRules.slotX(JoinStripRules.displayedSlot(index, dragFrom, dragTarget), padding, cell, gap) }
    }

    /** Moves the other squares to their new places on screen. */
    private fun slideTo(target: (Int) -> Float) {
        slide?.cancel()
        val from = ArrayList(shown)
        val to = clips.indices.map(target)
        if (!Motion.movementEnabled()) { shown.clear(); shown.addAll(to); invalidate(); return }
        slide = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = Motion.MODAL_MS
            interpolator = Motion.easeInOut
            addUpdateListener {
                val f = it.animatedValue as Float
                to.indices.forEach { i -> shown[i] = from.getOrElse(i) { to[i] } + (to[i] - from.getOrElse(i) { to[i] }) * f }
                invalidate()
            }
            start()
        }
    }

    private fun drop() {
        val from = dragFrom
        val to = dragTarget
        val landing = JoinStripRules.slotX(to, padding, cell, gap)
        val start = dragX
        animateLift(0f)
        val settle = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (Motion.movementEnabled()) Motion.POPOVER_MS else 0L
            interpolator = Motion.easeOut
            addUpdateListener { dragX = start + (landing - start) * (it.animatedValue as Float); invalidate() }
        }
        settle.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                dragFrom = -1
                dragTarget = -1
                slide?.cancel()
                if (from != to) onMove?.invoke(from, to) else { resetShown(); invalidate() }
            }
        })
        settle.start()
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.join_add, context.getString(R.string.join_add)))
        if (selected in 0 until clips.lastIndex) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.join_transition, context.getString(R.string.join_transition)))
        if (selected > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.join_move_left, context.getString(R.string.join_move_left)))
        if (selected in 0 until clips.lastIndex) info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.join_move_right, context.getString(R.string.join_move_right)))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
        R.id.join_add -> { onAdd?.invoke(); true }
        R.id.join_transition -> { onJunction?.invoke(selected, junctionCenter(selected)); true }
        R.id.join_move_left -> { onMove?.invoke(selected, selected - 1); true }
        R.id.join_move_right -> { onMove?.invoke(selected, selected + 1); true }
        else -> super.performAccessibilityAction(action, arguments)
    }
}
