package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs

/** Scrolls a virtual, second-by-second filmstrip under a fixed playhead. */
internal class VideoTimelineView(context: Context) : View(context) {
    var onScrubStart: (() -> Unit)? = null
    var onScrubMove: ((Long) -> Unit)? = null
    var onScrubStop: ((Long, Boolean) -> Unit)? = null
    var durationMs = 0L
        private set
    var positionMs = 0L
        private set
    var isScrubbing = false
        private set
    private var source: VideoTimelineFrames? = null
    private var sourceIdentity: String? = null
    private var downX = 0f
    private var downPosition = 0L
    private var moved = false
    private var samples = emptyList<Long>()
    private val frames = HashMap<Long, Bitmap>()
    private val failedFrames = HashSet<Long>()
    private var expansionRequested = false
    private var preparationEnabled = true
    private val unavailableFrame by lazy { context.getDrawable(R.drawable.ic_movie) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private var range: LongRange? = null
    private val cellWidth get() = dp(54).toFloat()
    val isPrepared: Boolean
        get() {
            if (source == null || durationMs <= 0L) return false
            val visible = visibleTimes(viewportWidth())
            return visible.isNotEmpty() && visible.all { it in frames || it in failedFrames }
        }

    init {
        tag = "video_timeline"
        isFocusable = true
        isClickable = true
        contentDescription = context.getString(R.string.video_timeline_description)
    }

    fun setSource(uri: Uri?, revision: String = "") {
        val identity = uri?.let { "$it|$revision" }
        if (identity == sourceIdentity) return
        cancelGesture()
        source?.close()
        source = uri?.let { VideoTimelineFrames(context, it, revision) }
        sourceIdentity = identity
        frames.clear()
        failedFrames.clear()
        samples = emptyList()
        durationMs = 0L
        positionMs = 0L
        range = null
        if (expansionRequested) { animate().cancel(); alpha = 1f; visibility = GONE }
        invalidate()
    }

    fun update(position: Long, duration: Long) {
        durationMs = duration.coerceAtLeast(0L)
        if (!isScrubbing) positionMs = position.coerceIn(0L, durationMs)
        requestFrames()
        invalidate()
    }

    fun setSelectedRange(selected: LongRange?) { range = selected; invalidate() }

    /** Preparing the viewport starts with playback; opening never reveals loading cells. */
    fun setExpanded(expanded: Boolean) {
        expansionRequested = expanded
        if (!expanded) {
            cancelGesture()
            animate().cancel()
            alpha = 1f
            visibility = GONE
        } else {
            if (!isPrepared) { animate().cancel(); alpha = 1f; visibility = GONE }
            requestFrames()
            revealIfPrepared()
        }
    }

    fun setPreparationEnabled(enabled: Boolean) {
        preparationEnabled = enabled
        if (enabled) requestFrames() else {
            source?.request(emptyList()) { _, _ -> }
            samples = emptyList()
        }
    }

    private fun revealIfPrepared() {
        if (!expansionRequested || !preparationEnabled || visibility == VISIBLE || !isPrepared) return
        visibility = VISIBLE
        alpha = 0f
        animate().alpha(1f).setDuration(120L).start()
    }

    private fun viewportWidth(): Int {
        val container = parent as? View
        val available = container?.let { it.width - it.paddingLeft - it.paddingRight } ?: 0
        return available.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?:
            (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(1)
    }

    private fun visibleTimes(viewport: Int): List<Long> =
        VideoTimelineRules.visibleIndices(positionMs, durationMs, viewport, cellWidth)
            .filter { index ->
                val offset = (index.toDouble() * VideoTimelineRules.FRAME_INTERVAL_MS - positionMs) /
                    VideoTimelineRules.FRAME_INTERVAL_MS * cellWidth
                abs(offset) <= (viewport + cellWidth) / 2.0
            }.map { VideoTimelineRules.frameTime(it, durationMs) }.distinct()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { requestFrames() }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // View can invoke this during construction, before the fields are initialized.
        if (visibility == VISIBLE) post { requestFrames() }
        else if (source != null) cancelGesture()
    }

    private fun requestFrames() {
        if (!preparationEnabled || !isAttachedToWindow || windowVisibility != VISIBLE || durationMs <= 0L) return
        val indices = VideoTimelineRules.visibleIndices(positionMs, durationMs, viewportWidth(), cellWidth)
        // A few seconds ahead keep the closed strip ready as playback advances.
        val last = (indices.last + 3L).coerceAtMost(VideoTimelineRules.frameCount(durationMs) - 1L)
        val next = (indices.first..last).map { VideoTimelineRules.frameTime(it, durationMs) }.distinct()
        if (next == samples) { revealIfPrepared(); return }
        samples = next
        frames.keys.retainAll(next.toSet())
        failedFrames.retainAll(next.toSet())
        val priority = next.sortedBy { abs(it - positionMs) }
        source?.request(priority, onFailure = { time ->
            if (time in samples) { failedFrames.add(time); revealIfPrepared(); invalidate() }
        }) { time, bitmap ->
            if (time in samples) { frames[time] = bitmap; revealIfPrepared(); invalidate() }
        }
        revealIfPrepared()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = dp(7).toFloat()
        val bottom = height - dp(7).toFloat()
        for (index in VideoTimelineRules.visibleIndices(positionMs, durationMs, width, cellWidth)) {
            val time = VideoTimelineRules.frameTime(index, durationMs)
            val center = width / 2f + ((index.toDouble() * VideoTimelineRules.FRAME_INTERVAL_MS - positionMs) /
                VideoTimelineRules.FRAME_INTERVAL_MS * cellWidth).toFloat()
            dstRect.set(center - cellWidth / 2f, top, center + cellWidth / 2f - dp(1), bottom)
            if (dstRect.right < 0f || dstRect.left > width) continue
            val bitmap = frames[time]
            paint.color = 0xFF303237.toInt()
            canvas.drawRoundRect(dstRect, dp(3).toFloat(), dp(3).toFloat(), paint)
            if (bitmap != null) {
                val scale = maxOf(dstRect.width() / bitmap.width, dstRect.height() / bitmap.height)
                val cropWidth = (dstRect.width() / scale).toInt().coerceIn(1, bitmap.width)
                val cropHeight = (dstRect.height() / scale).toInt().coerceIn(1, bitmap.height)
                val left = (bitmap.width - cropWidth) / 2
                val y = (bitmap.height - cropHeight) / 2
                srcRect.set(left, y, left + cropWidth, y + cropHeight)
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            } else if (time in failedFrames) {
                unavailableFrame?.apply {
                    val x = dstRect.centerX().toInt()
                    val y = dstRect.centerY().toInt()
                    setBounds(x - dp(9), y - dp(9), x + dp(9), y + dp(9))
                    draw(canvas)
                }
            }
        }
        range?.let { selected ->
            paint.color = 0xAA000000.toInt()
            canvas.drawRect(0f, top, xAt(selected.first), bottom, paint)
            canvas.drawRect(xAt(selected.last), top, width.toFloat(), bottom, paint)
        }
        val x = width / 2f
        paint.color = Color.BLACK
        canvas.drawRoundRect(x - dp(3), top - dp(4), x + dp(3), bottom + dp(4), dp(3).toFloat(), dp(3).toFloat(), paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(x - dp(1), top - dp(4), x + dp(1), bottom + dp(4), dp(1).toFloat(), dp(1).toFloat(), paint)
    }

    private fun xAt(time: Long): Float =
        (width / 2.0 + (time - positionMs).toDouble() /
            VideoTimelineRules.FRAME_INTERVAL_MS * cellWidth).toFloat().coerceIn(0f, width.toFloat())

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || durationMs <= 0L || width <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isScrubbing = true
                moved = false
                downX = event.x
                downPosition = positionMs
                onScrubStart?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isScrubbing) return true
                if (abs(event.x - downX) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
                if (moved) choose(VideoTimelineRules.positionAfterDrag(downPosition, downX - event.x, cellWidth, durationMs))
            }
            MotionEvent.ACTION_UP -> {
                if (!isScrubbing) return true
                if (!moved) {
                    choose(VideoTimelineRules.positionAfterDrag(downPosition, event.x - width / 2f, cellWidth, durationMs))
                }
                finishGesture(false)
                if (!moved) performClick()
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finishGesture(true)
        }
        return true
    }

    private fun choose(position: Long) {
        positionMs = position
        onScrubMove?.invoke(positionMs)
        requestFrames()
        invalidate()
    }

    private fun finishGesture(cancelled: Boolean) {
        if (!isScrubbing) return
        isScrubbing = false
        if (cancelled) positionMs = downPosition
        onScrubStop?.invoke(positionMs, cancelled)
        parent?.requestDisallowInterceptTouchEvent(false)
        requestFrames()
        invalidate()
    }

    fun cancelGesture() { finishGesture(true) }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.SeekBar"
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, 0f, durationMs / 1000f, positionMs / 1000f)
        if (Build.VERSION.SDK_INT >= 24) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val target = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> positionMs + 1000L
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> positionMs - 1000L
            android.R.id.accessibilityActionSetProgress -> {
                if (Build.VERSION.SDK_INT < 24) return false
                ((arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false) * 1000L).toLong()
            }
            else -> return super.performAccessibilityAction(action, arguments)
        }
        seekAccessible(target)
        return true
    }

    private fun seekAccessible(target: Long) {
        onScrubStart?.invoke()
        choose(target.coerceIn(0L, durationMs))
        onScrubStop?.invoke(positionMs, false)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT -> { seekAccessible(positionMs - 1000L); true }
        KeyEvent.KEYCODE_DPAD_RIGHT -> { seekAccessible(positionMs + 1000L); true }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onDetachedFromWindow() {
        cancelGesture()
        source?.close()
        source = null
        sourceIdentity = null
        frames.clear()
        failedFrames.clear()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = Ui.dp(context, value)
}
