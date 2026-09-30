package com.galeria.android

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View

/** Horizontal hue, vertical saturation/value; white and black remain reachable. */
internal class EditorColorSpectrum(context: Context, private val onColor: (Int) -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var selectedX = 0.5f
    private var selectedY = 0.25f
    init { contentDescription = "Espectro de cores do pincel e texto"; isFocusable = true }
    override fun onDraw(canvas: Canvas) {
        val hues = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }
        paint.shader = LinearGradient(0f, 0f, width.toFloat(), 0f, hues, null, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), intArrayOf(Color.WHITE, Color.TRANSPARENT, Color.BLACK), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Ui.dp(context, 2).toFloat()
        paint.color = Color.WHITE
        canvas.drawCircle(selectedX * width, selectedY * height, Ui.dp(context, 8).toFloat(), paint)
        paint.style = Paint.Style.FILL
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                selectedX = (event.x / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                selectedY = (event.y / height.coerceAtLeast(1)).coerceIn(0f, 1f)
                onColor(Color.HSVToColor(floatArrayOf(selectedX * 360f, (selectedY * 2f).coerceAtMost(1f), ((1f - selectedY) * 2f).coerceAtMost(1f))))
                invalidate()
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }
    override fun performClick(): Boolean = super.performClick()
}
