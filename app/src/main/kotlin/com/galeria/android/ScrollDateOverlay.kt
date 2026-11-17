package com.galeria.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** Texts shown while scrolling an album: the balloon by the finger, the year marks and the year on top. */
internal object ScrollDateRules {
    /**
     * Dates only make sense while the album runs in date order: sorted by date, or the custom order
     * before any media was placed by hand (it falls back to newest first). Any other order shows none.
     */
    fun followsDate(sortMode: String, manualOrder: Boolean = false): Boolean =
        sortMode == MediaSortRules.SORT_DATE || (sortMode == MediaSortRules.SORT_CUSTOM && !manualOrder)

    fun year(dateSeconds: Long, zone: TimeZone = TimeZone.getDefault()): Int? {
        if (dateSeconds <= 0L) return null
        return Calendar.getInstance(zone).apply { timeInMillis = dateSeconds * 1000L }.get(Calendar.YEAR)
    }

    /** "Jan. de 2026" in Portuguese, "Jan 2026" in English; first letter in upper case. */
    fun monthYear(
        dateSeconds: Long,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
        pattern: String = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMyyyy")
    ): String? {
        if (dateSeconds <= 0L) return null
        val format = java.text.SimpleDateFormat(pattern, locale).apply { timeZone = zone }
        return format.format(java.util.Date(dateSeconds * 1000L)).replaceFirstChar { it.titlecase(locale) }
    }

    /** The balloon next to the finger always says the month and year of the media under it. */
    fun balloon(item: MediaItem): String? = monthYear(item.dateAdded)

    /**
     * First position of each year, sampled across the album so huge albums stay cheap.
     * Positions whose media is not loaded yet return null and are skipped.
     */
    fun yearMarks(total: Int, samples: Int = 400, yearAt: (Int) -> Int?): List<Pair<Int, Int>> {
        if (total <= 0) return emptyList()
        val step = maxOf(1, total / samples.coerceAtLeast(1))
        val marks = ArrayList<Pair<Int, Int>>()
        var lastYear: Int? = null
        var position = 0
        while (position < total) {
            val year = yearAt(position)
            if (year != null && year != lastYear) {
                marks.add(position to year)
                lastYear = year
            }
            position += step
        }
        return marks
    }
}

/**
 * Transparent layer over the album grid. It never takes touches; it only draws the balloon next
 * to the fast scroller, the year marks along it and the year at the top while the grid moves.
 */
internal class ScrollDateOverlay(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = density }
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val markText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * resources.displayMetrics.scaledDensity
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.RIGHT
    }
    private val markBackground = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var bubble: String? = null
    private var bubbleY = 0f
    private var rightEdge = 0f
    private var marks: List<Pair<Float, String>> = emptyList()
    private var topLabel: String? = null
    private var topAlpha = 0f

    // What is on screen, for tests and accessibility checks.
    internal val shownBubble: String? get() = bubble
    internal val shownMarks: List<String> get() = marks.map { it.second }
    internal val shownTop: String? get() = topLabel?.takeIf { topAlpha > 0f }
    private val fadeTop = Runnable { animateTop(0f) }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
        restyle()
    }

    fun restyle() {
        bubblePaint.color = Ui.menuSurface(context)
        bubbleBorder.color = Ui.menuBorder(context)
        bubbleText.color = Ui.menuText(context)
        markText.color = Ui.menuText(context)
        markBackground.color = (Ui.menuSurface(context) and 0x00FFFFFF) or 0xD9000000.toInt()
        invalidate()
    }

    /** Balloon centred at [centerY], ending at [right] (both in this view's coordinates). */
    fun showBubble(text: String?, centerY: Float, right: Float) {
        if (text != null) bubble = text
        bubbleY = centerY
        rightEdge = right
        invalidate()
    }

    fun setMarks(values: List<Pair<Float, String>>, right: Float) {
        marks = values
        rightEdge = right
        invalidate()
    }

    fun hideScrubbing() {
        bubble = null
        marks = emptyList()
        invalidate()
    }

    /** The year on top; it fades out a moment after the grid stops. */
    fun showTop(text: String?) {
        if (text == null) return
        topLabel = text
        removeCallbacks(fadeTop)
        if (topAlpha < 1f) animateTop(1f)
        postDelayed(fadeTop, TOP_VISIBLE_MS)
    }

    private fun animateTop(target: Float) {
        val start = topAlpha
        val began = android.os.SystemClock.uptimeMillis()
        val step = object : Runnable {
            override fun run() {
                val progress = ((android.os.SystemClock.uptimeMillis() - began) / FADE_MS.toFloat()).coerceIn(0f, 1f)
                topAlpha = start + (target - start) * progress
                invalidate()
                if (progress < 1f) postOnAnimation(this)
            }
        }
        postOnAnimation(step)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(fadeTop)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawMarks(canvas)
        drawBubble(canvas)
        drawTop(canvas)
    }

    private fun drawMarks(canvas: Canvas) {
        if (marks.isEmpty()) return
        val x = rightEdge - 4 * density
        val metrics = markText.fontMetrics
        var lastY = -Float.MAX_VALUE
        for ((y, text) in marks) {
            if (abs(y - lastY) < 22 * density) continue
            if (bubble != null && abs(y - bubbleY) < 26 * density) continue
            val width = markText.measureText(text)
            rect.set(x - width - 8 * density, y - 10 * density, x + 6 * density, y + 10 * density)
            canvas.drawRoundRect(rect, 10 * density, 10 * density, markBackground)
            canvas.drawText(text, x, y - (metrics.ascent + metrics.descent) / 2f, markText)
            lastY = y
        }
    }

    private fun drawBubble(canvas: Canvas) {
        val text = bubble ?: return
        val width = bubbleText.measureText(text)
        val height = 44 * density
        val right = rightEdge - 6 * density
        val top = (bubbleY - height / 2f).coerceIn(4 * density, this.height - height - 4 * density)
        rect.set(right - width - 36 * density, top, right, top + height)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, bubblePaint)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, bubbleBorder)
        val metrics = bubbleText.fontMetrics
        canvas.drawText(text, rect.left + 18 * density, rect.centerY() - (metrics.ascent + metrics.descent) / 2f, bubbleText)
    }

    private fun drawTop(canvas: Canvas) {
        val text = topLabel ?: return
        if (topAlpha <= 0f) return
        val alpha = (topAlpha * 255).roundToInt()
        bubblePaint.alpha = alpha
        bubbleBorder.alpha = alpha
        bubbleText.alpha = alpha
        val width = bubbleText.measureText(text)
        val height = 40 * density
        val left = (this.width - width) / 2f - 22 * density
        rect.set(left, 12 * density, left + width + 44 * density, 12 * density + height)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, bubblePaint)
        canvas.drawRoundRect(rect, height / 2f, height / 2f, bubbleBorder)
        val metrics = bubbleText.fontMetrics
        canvas.drawText(text, rect.left + 22 * density, rect.centerY() - (metrics.ascent + metrics.descent) / 2f, bubbleText)
        bubblePaint.alpha = 255
        bubbleBorder.alpha = 255
        bubbleText.alpha = 255
    }

    private companion object {
        const val TOP_VISIBLE_MS = 1100L
        const val FADE_MS = 180L
    }
}
