package com.galeria.android

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/** Horizontal hue, vertical saturation/value; white and black remain reachable. */
internal class EditorColorSpectrum(context: Context, private val onColor: (Int) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
    private val hsv = FloatArray(3)
    private val markerStroke = Ui.dp(context, 2).toFloat()
    private val markerRadius = Ui.dp(context, 8).toFloat()
    private var selectedX = 0.5f
    private var selectedY = 0.25f
    init { contentDescription = context.getString(R.string.msg_color_spectrum); isFocusable = true }

    // The gradients depend only on the size, so they are built here instead of on every frame.
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        huePaint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, hues, null, Shader.TileMode.CLAMP)
        shadePaint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(Color.WHITE, Color.TRANSPARENT, Color.BLACK), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), huePaint)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shadePaint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = markerStroke
        paint.color = Color.WHITE
        canvas.drawCircle(selectedX * width, selectedY * height, markerRadius, paint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                selectedX = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                selectedY = (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                hsv[0] = selectedX * 360f
                hsv[1] = (selectedY * 2f).coerceAtMost(1f)
                hsv[2] = ((1f - selectedY) * 2f).coerceAtMost(1f)
                onColor(Color.HSVToColor(hsv))
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean = super.performClick()
}
