package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

internal class ThemeColorWheelView(context: Context) : View(context) {
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 1).toFloat()
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = Ui.dp(context, 3).toFloat()
    }
    private var wheelBitmap: Bitmap? = null
    private var wheelRadius = 0f
    private var hue = 0f
    private var saturation = 0f
    private var tone = 0.5f

    var onColorChanged: ((Int) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun setColor(color: Int, notify: Boolean = false) {
        val hsv = FloatArray(3)
        Color.colorToHSV(Ui.normalizeThemeSeed(color), hsv)
        hue = hsv[0]
        saturation = hsv[1].coerceIn(0f, MAX_SATURATION)
        tone = hsv[2].coerceIn(MIN_TONE, MAX_TONE)
        invalidate()
        if (notify) onColorChanged?.invoke(selectedColor())
    }

    fun setTone(value: Float, notify: Boolean = true) {
        tone = value.coerceIn(MIN_TONE, MAX_TONE)
        invalidate()
        if (notify) onColorChanged?.invoke(selectedColor())
    }

    fun selectedColor(): Int = Color.HSVToColor(floatArrayOf(hue, saturation, tone))

    fun selectedTone(): Float = tone

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        wheelRadius = min(width, height) / 2f - Ui.dp(context, 10)
        if (width <= 0 || height <= 0 || wheelRadius <= 0f) return
        val centerX = width / 2f
        val centerY = height / 2f
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = x - centerX
                val dy = y - centerY
                val distance = hypot(dx, dy)
                if (distance <= wheelRadius) {
                    val pixelHue = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                        .let { if (it < 0f) it + 360f else it }
                    val pixelSaturation = (distance / wheelRadius * MAX_SATURATION).coerceIn(0f, MAX_SATURATION)
                    pixels[y * width + x] = Color.HSVToColor(floatArrayOf(pixelHue, pixelSaturation, 0.92f))
                }
            }
        }
        wheelBitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = wheelBitmap ?: return
        val centerX = width / 2f
        val centerY = height / 2f
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        borderPaint.color = 0x66000000
        canvas.drawCircle(centerX, centerY, wheelRadius, borderPaint)

        val angle = Math.toRadians(hue.toDouble())
        val thumbDistance = wheelRadius * saturation / MAX_SATURATION
        val thumbX = centerX + cos(angle).toFloat() * thumbDistance
        val thumbY = centerY + sin(angle).toFloat() * thumbDistance
        thumbPaint.color = if (Ui.luminance(selectedColor()) >= 0.56) Color.BLACK else Color.WHITE
        canvas.drawCircle(thumbX, thumbY, Ui.dp(context, 9).toFloat(), thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked != MotionEvent.ACTION_DOWN &&
            event.actionMasked != MotionEvent.ACTION_MOVE &&
            event.actionMasked != MotionEvent.ACTION_UP
        ) return super.onTouchEvent(event)

        parent?.requestDisallowInterceptTouchEvent(true)
        val centerX = width / 2f
        val centerY = height / 2f
        val dx = event.x - centerX
        val dy = event.y - centerY
        val distance = hypot(dx, dy).coerceAtMost(wheelRadius)
        hue = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            .let { if (it < 0f) it + 360f else it }
        saturation = (distance / wheelRadius * MAX_SATURATION).coerceIn(0f, MAX_SATURATION)
        invalidate()
        onColorChanged?.invoke(selectedColor())
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            parent?.requestDisallowInterceptTouchEvent(false)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    companion object {
        const val MAX_SATURATION = 0.48f
        const val MIN_TONE = 0.18f
        const val MAX_TONE = 0.94f
        const val TAG = "theme_color_wheel"
    }
}
