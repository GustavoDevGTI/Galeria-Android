package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToLong

/** Overview by default; hold to spread a 20-second window under the finger. */
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
    private var startMs = 0L
    private var spanMs = 0L
    private var fine = false
    private var downX = 0f
    private var lastX = 0f
    private var moved = false
    private var samples = emptyList<Long>()
    private val frames = HashMap<Long, Bitmap>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private var range: LongRange? = null
    private val finePress = Runnable {
        if (isScrubbing && durationMs > 20_000L) {
            fine = true
            spanMs = VideoTimelineRules.fineSpan(durationMs)
            startMs = VideoTimelineRules.windowStart(positionMs, spanMs, durationMs)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            requestFrames()
            invalidate()
        }
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
        samples = emptyList()
        durationMs = 0L
        positionMs = 0L
        range = null
        invalidate()
    }

    fun update(position: Long, duration: Long) {
        val nextDuration = duration.coerceAtLeast(0L)
        if (durationMs != nextDuration) {
            durationMs = nextDuration
            if (!isScrubbing) { startMs = 0; spanMs = nextDuration }
            requestFrames()
        }
        if (!isScrubbing) positionMs = position.coerceIn(0L, durationMs)
        invalidate()
    }

    fun setSelectedRange(selected: LongRange?) { range = selected; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { requestFrames() }

    private fun requestFrames() {
        if (width <= 0 || durationMs <= 0) return
        val next = VideoTimelineRules.samples(startMs, spanMs, durationMs, ceil(width / dp(54).toDouble()).toInt())
        if (next == samples) return
        samples = next
        frames.keys.retainAll(next.toSet())
        source?.request(next) { time, bitmap ->
            if (time in samples) { frames[time] = bitmap; invalidate() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val top = dp(7).toFloat()
        val bottom = height - dp(7).toFloat()
        val cell = width.toFloat() / samples.size.coerceAtLeast(1)
        for ((index, time) in samples.withIndex()) {
            dstRect.set(index * cell, top, (index + 1) * cell - dp(1), bottom)
            val bitmap = frames[time]
            paint.color = 0xFF303237.toInt()
            canvas.drawRoundRect(dstRect, dp(3).toFloat(), dp(3).toFloat(), paint)
            if (bitmap != null) {
                val scale = maxOf(dstRect.width() / bitmap.width, dstRect.height() / bitmap.height)
                val cropWidth = (dstRect.width() / scale).toInt().coerceAtLeast(1)
                val cropHeight = (dstRect.height() / scale).toInt().coerceAtLeast(1)
                val left = (bitmap.width - cropWidth) / 2
                val y = (bitmap.height - cropHeight) / 2
                srcRect.set(left, y, left + cropWidth, y + cropHeight)
                canvas.drawBitmap(bitmap, srcRect, dstRect, paint)
            }
        }
        range?.let { selected ->
            paint.color = 0xAA000000.toInt()
            canvas.drawRect(0f, top, xAt(selected.first), bottom, paint)
            canvas.drawRect(xAt(selected.last), top, width.toFloat(), bottom, paint)
        }
        val x = xAt(positionMs).coerceIn(dp(2).toFloat(), (width - dp(2)).coerceAtLeast(dp(2)).toFloat())
        paint.color = Color.BLACK
        canvas.drawRoundRect(x - dp(3), top - dp(4), x + dp(3), bottom + dp(4), dp(3).toFloat(), dp(3).toFloat(), paint)
        paint.color = Color.WHITE
        canvas.drawRoundRect(x - dp(1), top - dp(4), x + dp(1), bottom + dp(4), dp(1).toFloat(), dp(1).toFloat(), paint)
        if (isScrubbing && durationMs > 20_000L) {
            val label = context.getString(if (fine) R.string.video_timeline_fine else R.string.video_timeline_hint)
            paint.textSize = dp(11).toFloat()
            val textWidth = paint.measureText(label)
            val left = (width - textWidth) / 2f
            paint.color = 0xDD161719.toInt()
            canvas.drawRoundRect(left - dp(8), bottom - dp(20), left + textWidth + dp(8), bottom, dp(8).toFloat(), dp(8).toFloat(), paint)
            paint.color = Color.WHITE
            canvas.drawText(label, left, bottom - dp(5), paint)
        }
    }

    private fun xAt(time: Long): Float =
        ((time - startMs).toDouble() / spanMs.coerceAtLeast(1) * width).toFloat().coerceIn(0f, width.toFloat())

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || durationMs <= 0 || width <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isScrubbing = true
                fine = false
                moved = false
                downX = event.x
                lastX = event.x
                onScrubStart?.invoke()
                choose(VideoTimelineRules.positionAt(event.x / width.toDouble(), startMs, spanMs, durationMs))
                postDelayed(finePress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > ViewConfiguration.get(context).scaledTouchSlop) {
                    moved = true
                    if (!fine) removeCallbacks(finePress)
                }
                if (fine) {
                    choose((positionMs + ((event.x - lastX) / width * spanMs).roundToLong()).coerceIn(0L, durationMs))
                    startMs = VideoTimelineRules.windowStart(positionMs, spanMs, durationMs)
                    requestFrames()
                } else choose(VideoTimelineRules.positionAt(event.x / width.toDouble(), startMs, spanMs, durationMs))
                lastX = event.x
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                finishGesture(cancelled)
                if (!cancelled && !moved) performClick()
            }
        }
        return true
    }

    private fun choose(position: Long) {
        positionMs = position
        onScrubMove?.invoke(positionMs)
        invalidate()
    }

    private fun finishGesture(cancelled: Boolean) {
        removeCallbacks(finePress)
        if (!isScrubbing) return
        isScrubbing = false
        fine = false
        onScrubStop?.invoke(positionMs, cancelled)
        parent?.requestDisallowInterceptTouchEvent(false)
        startMs = 0L
        spanMs = durationMs
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
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = Ui.dp(context, value)
}
