package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Where a finger on the strip lands in the clip, and which times the strip shows. */
internal object MotionFrameStripRules {
    fun timeAt(x: Float, width: Int, durationMs: Long): Long {
        if (width <= 0 || durationMs <= 0L) return 0L
        return ((x / width).coerceIn(0f, 1f) * durationMs).roundToInt().toLong().coerceIn(0L, durationMs)
    }

    fun xAt(timeMs: Long, width: Int, durationMs: Long): Float =
        if (width <= 0 || durationMs <= 0L) 0f else (timeMs.coerceIn(0L, durationMs).toFloat() / durationMs * width)

    /** One thumbnail per cell, sampled at the middle of the part of the clip each cell covers. */
    fun sampleTimes(cells: Int, durationMs: Long): List<Long> =
        if (cells <= 0 || durationMs <= 0L) emptyList()
        else (0 until cells).map { ((it + 0.5) * durationMs / cells).toLong() }
}

/**
 * The clip's frames across the whole width. Touching or dragging anywhere chooses that moment;
 * a white frame marks the chosen one.
 */
internal class MotionFrameStripView(context: Context) : View(context) {
    var onChoose: ((Long, Boolean) -> Unit)? = null
    var durationMs = 0L
        private set
    var positionMs = 0L
        private set
    private var clip: File? = null
    private val thumbnails = HashMap<Int, Bitmap>()
    private var loadedCells = 0
    private var lastCell = -1
    private val loader = Executors.newSingleThreadExecutor()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = Ui.dp(context, 3).toFloat()
    }
    private val shade = Paint().apply { color = 0x66000000 }
    private val src = Rect()
    private val dst = RectF()

    init {
        tag = "motion_frame_strip"
        isFocusable = true
        isClickable = true
        contentDescription = context.getString(R.string.motion_frame_strip)
    }

    fun setClip(file: File, duration: Long, position: Long) {
        clip = file
        durationMs = duration.coerceAtLeast(0L)
        positionMs = position.coerceIn(0L, durationMs)
        thumbnails.values.forEach(Bitmap::recycle)
        thumbnails.clear()
        loadedCells = 0
        requestThumbnails()
        invalidate()
    }

    private fun cells(): Int = if (width <= 0) 0 else (width / Ui.dp(context, 44)).coerceIn(4, 14)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { requestThumbnails() }

    private fun requestThumbnails() {
        val file = clip ?: return
        val count = cells()
        if (count == 0 || count == loadedCells || durationMs <= 0L) return
        loadedCells = count
        val side = height.coerceAtLeast(Ui.dp(context, 56))
        MotionFrameStripRules.sampleTimes(count, durationMs).forEachIndexed { index, time ->
            loader.execute {
                val bitmap = runCatching { MotionFrameEditor.frame(file, time * 1000L, side, exact = false) }.getOrNull()
                    ?: return@execute
                post {
                    if (clip !== file || loadedCells != count || !isAttachedToWindow) { bitmap.recycle(); return@post }
                    thumbnails.put(index, bitmap)?.recycle()
                    invalidate()
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val count = loadedCells
        if (count <= 0) return
        val cell = width.toFloat() / count
        val radius = Ui.dp(context, 10).toFloat()
        canvas.save()
        canvas.clipPath(android.graphics.Path().apply {
            addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), radius, radius, android.graphics.Path.Direction.CW)
        })
        canvas.drawColor(0xFF222222.toInt())
        for (index in 0 until count) {
            val bitmap = thumbnails[index] ?: continue
            dst.set(index * cell, 0f, (index + 1) * cell, height.toFloat())
            // Centre-crop each thumbnail into its cell.
            val scale = maxOf(dst.width() / bitmap.width, dst.height() / bitmap.height)
            val w = (dst.width() / scale).toInt()
            val h = (dst.height() / scale).toInt()
            src.set((bitmap.width - w) / 2, (bitmap.height - h) / 2, (bitmap.width + w) / 2, (bitmap.height + h) / 2)
            canvas.drawBitmap(bitmap, src, dst, paint)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        canvas.restore()
        val x = MotionFrameStripRules.xAt(positionMs, width, durationMs)
        val half = Ui.dp(context, 18).toFloat()
        val left = (x - half).coerceIn(0f, width - 2 * half)
        val inset = marker.strokeWidth / 2f
        canvas.drawRoundRect(left + inset, inset, left + 2 * half - inset, height - inset, radius / 2, radius / 2, marker)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || durationMs <= 0L) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); choose(event.x, false) }
            MotionEvent.ACTION_MOVE -> choose(event.x, false)
            MotionEvent.ACTION_UP -> { choose(event.x, true); performClick() }
            MotionEvent.ACTION_CANCEL -> onChoose?.invoke(positionMs, true)
        }
        return true
    }

    private fun choose(x: Float, final: Boolean) {
        val time = MotionFrameStripRules.timeAt(x, width, durationMs)
        val changed = time != positionMs
        positionMs = time
        if (changed) {
            invalidate()
            // One tick per thumbnail crossed, not per millisecond.
            val cell = if (durationMs > 0L && loadedCells > 0) (time * loadedCells / (durationMs + 1L)).toInt() else 0
            if (!final && cell != lastCell) Haptics.perform(this, Haptics.Effect.TICK)
            lastCell = cell
        }
        if (changed || final) onChoose?.invoke(positionMs, final)
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val step = (durationMs / 10L).coerceAtLeast(1L)
        val target = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> positionMs + step
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> positionMs - step
            else -> return super.performAccessibilityAction(action, arguments)
        }.coerceIn(0L, durationMs)
        positionMs = target
        invalidate()
        onChoose?.invoke(target, true)
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        loader.shutdownNow()
        thumbnails.values.forEach(Bitmap::recycle)
        thumbnails.clear()
    }
}
