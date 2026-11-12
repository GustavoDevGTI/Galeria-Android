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

/**
 * Círculo de cores: o ângulo escolhe o matiz e a distância ao centro escolhe o tom.
 * O centro é branco, a metade do raio mostra a cor mais viva permitida pelo tema
 * e a borda escurece até o preto, seguindo a paleta de cada linha.
 */
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
    private var radiusFraction = 0f
    private var selected = Color.WHITE

    var onColorChanged: ((Int) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
    }

    fun setColor(color: Int, notify: Boolean = false) {
        val hsv = FloatArray(3)
        Color.colorToHSV(Ui.normalizeThemeSeed(color), hsv)
        hsv[1] = hsv[1].coerceIn(0f, MAX_SATURATION)
        if (hsv[1] > 0.001f) hue = hsv[0]
        radiusFraction = radiusForColor(hsv[1], hsv[2])
        selected = Color.HSVToColor(hsv)
        invalidate()
        if (notify) onColorChanged?.invoke(selected)
    }

    fun selectedColor(): Int = selected

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
                    pixels[y * width + x] = colorAt(pixelHue, distance / wheelRadius)
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
        val thumbDistance = wheelRadius * radiusFraction
        val thumbX = centerX + cos(angle).toFloat() * thumbDistance
        val thumbY = centerY + sin(angle).toFloat() * thumbDistance
        thumbPaint.color = if (Ui.luminance(colorAt(hue, radiusFraction)) >= 0.56) Color.BLACK else Color.WHITE
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
        radiusFraction = if (wheelRadius > 0f) (distance / wheelRadius).coerceIn(0f, 1f) else 0f
        selected = colorAt(hue, radiusFraction)
        invalidate()
        onColorChanged?.invoke(selected)
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
        const val TAG = "theme_color_wheel"
        private const val VIVID_RING = 0.5f

        /** Cor de um ponto do círculo: branco no centro, cor viva no meio do raio e preto na borda. */
        @JvmStatic
        fun colorAt(hue: Float, fraction: Float): Int {
            val r = fraction.coerceIn(0f, 1f)
            return if (r <= VIVID_RING) {
                Color.HSVToColor(floatArrayOf(hue, MAX_SATURATION * r / VIVID_RING, 1f))
            } else {
                val darkening = (r - VIVID_RING) / (1f - VIVID_RING)
                Color.HSVToColor(floatArrayOf(hue, MAX_SATURATION, 1f - darkening))
            }
        }

        /** Posição aproximada no raio para uma cor já salva, usada para colocar o marcador. */
        @JvmStatic
        fun radiusForColor(saturation: Float, value: Float): Float {
            val lightLimit = 0.995f
            return when {
                value >= lightLimit && saturation < MAX_SATURATION - 0.01f ->
                    VIVID_RING * (saturation / MAX_SATURATION).coerceIn(0f, 1f)
                value >= lightLimit -> VIVID_RING
                else -> VIVID_RING + (1f - VIVID_RING) * (1f - value).coerceIn(0f, 1f)
            }
        }
    }
}
