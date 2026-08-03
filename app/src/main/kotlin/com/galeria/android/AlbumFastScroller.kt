package com.galeria.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.max
import kotlin.math.roundToInt

/** Barra lateral arrastável para navegar rapidamente por álbuns extensos. */
class AlbumFastScroller(
    context: Context,
    private val recyclerView: RecyclerView
) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.muted(context)
        alpha = 70
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Ui.accent(context)
    }
    private val trackRect = RectF()
    private val thumbRect = RectF()
    private val minThumbHeight = Ui.dp(context, 48).toFloat()
    private val trackWidth = Ui.dp(context, 3).toFloat()
    private val thumbWidth = Ui.dp(context, 6).toFloat()
    private var thumbTop = 0f
    private var thumbHeight = minThumbHeight
    private var dragOffset = 0f
    private var dragging = false
    private var listenersRegistered = false

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = updateThumb()
    }

    private val dataObserver = object : RecyclerView.AdapterDataObserver() {
        override fun onChanged() = updateThumb()
        override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = updateThumb()
        override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = updateThumb()
        override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = updateThumb()
    }

    init {
        contentDescription = "Rolagem rápida do álbum"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
        registerListeners()
        post(::updateThumb)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerListeners()
        post(::updateThumb)
    }

    override fun onDetachedFromWindow() {
        unregisterListeners()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (visibility != VISIBLE || height <= 0) return
        val centerX = width - Ui.dp(context, 7).toFloat()
        val trackRadius = trackWidth / 2f
        trackRect.set(
            centerX - trackWidth / 2f,
            paddingTop.toFloat(),
            centerX + trackWidth / 2f,
            (height - paddingBottom).toFloat()
        )
        canvas.drawRoundRect(trackRect, trackRadius, trackRadius, trackPaint)

        val thumbRadius = thumbWidth / 2f
        thumbRect.set(
            centerX - thumbWidth / 2f,
            thumbTop,
            centerX + thumbWidth / 2f,
            thumbTop + thumbHeight
        )
        canvas.drawRoundRect(thumbRect, thumbRadius, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (visibility != VISIBLE) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                recyclerView.stopScroll()
                dragging = true
                dragOffset = if (event.y in thumbTop..(thumbTop + thumbHeight)) {
                    event.y - thumbTop
                } else {
                    thumbHeight / 2f
                }
                scrollToTouch(event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return false
                scrollToTouch(event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return false
                scrollToTouch(event.y)
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun scrollToTouch(y: Float) {
        val adapter = recyclerView.adapter ?: return
        val itemCount = adapter.itemCount
        if (itemCount <= 1) return
        val trackTop = paddingTop.toFloat()
        val travel = max(1f, height - paddingTop - paddingBottom - thumbHeight)
        val desiredTop = (y - dragOffset).coerceIn(trackTop, trackTop + travel)
        val fraction = (desiredTop - trackTop) / travel
        val targetPosition = (fraction * (itemCount - 1)).roundToInt()
        (recyclerView.layoutManager as? GridLayoutManager)
            ?.scrollToPositionWithOffset(targetPosition, 0)
            ?: recyclerView.scrollToPosition(targetPosition)
        thumbTop = desiredTop
        invalidate()
    }

    private fun updateThumb() {
        if (dragging || height <= 0) return
        val layoutManager = recyclerView.layoutManager as? GridLayoutManager ?: return hide()
        val total = recyclerView.adapter?.itemCount ?: 0
        val first = layoutManager.findFirstVisibleItemPosition()
        val last = layoutManager.findLastVisibleItemPosition()
        if (total <= 0 || first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) {
            hide()
            return
        }
        val visibleItems = max(1, last - first + 1)
        if (total <= visibleItems) {
            hide()
            return
        }
        visibility = VISIBLE
        val trackHeight = max(1f, (height - paddingTop - paddingBottom).toFloat())
        thumbHeight = max(minThumbHeight, trackHeight * visibleItems / total).coerceAtMost(trackHeight)
        val travel = max(0f, trackHeight - thumbHeight)
        val scrollableItems = max(1, total - visibleItems)
        thumbTop = paddingTop + travel * first.coerceAtMost(scrollableItems) / scrollableItems
        invalidate()
    }

    private fun hide() {
        visibility = INVISIBLE
        invalidate()
    }

    private fun registerListeners() {
        if (listenersRegistered) return
        recyclerView.addOnScrollListener(scrollListener)
        recyclerView.adapter?.registerAdapterDataObserver(dataObserver)
        listenersRegistered = true
    }

    private fun unregisterListeners() {
        if (!listenersRegistered) return
        recyclerView.removeOnScrollListener(scrollListener)
        recyclerView.adapter?.unregisterAdapterDataObserver(dataObserver)
        listenersRegistered = false
    }
}
