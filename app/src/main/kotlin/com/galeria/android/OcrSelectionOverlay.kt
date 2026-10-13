package com.galeria.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.customview.widget.ExploreByTouchHelper
import kotlin.math.hypot

/** Transparent selection layer. Never replaces, redraws or changes image pixels.
 * Normalized OCR geometry is mapped through the image's current draw matrix. */
internal class OcrSelectionOverlay @JvmOverloads constructor(
    context: Context,
    val document: OcrDocument = OcrDocument(""),
    private val normalizedToView: () -> Matrix = { Matrix() },
    private val zoomTarget: View? = null,
    private val onClosed: () -> Unit = {}
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val blue = 0xFF518DFF.toInt()
    private val outlines = ArrayList<FloatArray>()
    private val bounds = ArrayList<RectF>()
    private var lastMatrix: Matrix? = null
    private var toolbar: LinearLayout? = null
    private var copyButton: TextView? = null
    private var closed = false
    private var first = -1
    private var last = -1
    private var anchor = -1
    private var draggingHandle = 0
    private var forwardingZoom = false
    private var previousDown: MotionEvent? = null
    val selectedText: String get() = if (first >= 0) document.selectedText(first, last) else ""
    private val preDraw = ViewTreeObserver.OnPreDrawListener { updateGeometry(); true }

    private val accessibility = object : ExploreByTouchHelper(this) {
        override fun getVirtualViewAt(x: Float, y: Float): Int = hitWord(x, y).takeIf { it >= 0 } ?: INVALID_ID
        override fun getVisibleVirtualViews(ids: MutableList<Int>) {
            updateGeometry()
            bounds.forEachIndexed { index, rect ->
                if (rect.width() > 0 && rect.height() > 0 && RectF.intersects(rect, RectF(0f, 0f, width.toFloat(), height.toFloat()))) ids.add(index)
            }
        }
        override fun onPopulateNodeForVirtualView(id: Int, node: AccessibilityNodeInfoCompat) {
            node.text = document.words[id].text
            node.className = "android.widget.TextView"
            node.isClickable = true
            node.isSelected = id in first..last && first >= 0
            val rect = Rect()
            bounds[id].roundOut(rect)
            node.setBoundsInParent(rect)
            node.addAction(AccessibilityNodeInfoCompat.ACTION_CLICK)
        }
        override fun onPerformActionForVirtualView(id: Int, action: Int, args: Bundle?): Boolean {
            if (action != AccessibilityNodeInfoCompat.ACTION_CLICK) return false
            select(id, id)
            return true
        }
    }

    init {
        tag = "ocr_image_selection"
        contentDescription = context.getString(R.string.ocr_select_on_image)
        isFocusable = true
        isClickable = true
        ViewCompat.setAccessibilityDelegate(this, accessibility)
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.action_select_all)) { _, _ -> selectAll(); true }
        ViewCompat.addAccessibilityAction(this, context.getString(R.string.ocr_copy_selection)) { _, _ -> copy(selectedText); true }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun attachTo(parent: FrameLayout) {
        parent.addView(this, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fun button(label: Int, action: () -> Unit) = TextView(context).apply {
            setText(label)
            textSize = 14f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            minimumHeight = Ui.dp(context, 48)
            isClickable = true; isFocusable = true
            setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), 0)
            background = Ui.actionFeedback(context, Color.WHITE)
            setOnClickListener { action() }
        }
        toolbar = LinearLayout(context).apply {
            tag = "ocr_selection_actions"
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(0xFF242424.toInt(), 20, context)
            elevation = Ui.dp(context, 6).toFloat()
            setPadding(Ui.dp(context, 4), 0, Ui.dp(context, 4), 0)
            addView(button(R.string.ocr_copy_selection) { copy(selectedText) }.also { copyButton = it })
            addView(button(R.string.action_select_all) { selectAll() })
            addView(EditorUi.button(context, android.R.drawable.ic_menu_close_clear_cancel,
                context.getString(R.string.action_close)) { close() }, LinearLayout.LayoutParams(Ui.dp(context, 48), Ui.dp(context, 48)))
        }.also { parent.addView(it, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        post { if (!closed) { updateGeometry(); positionToolbar() } }
    }

    private fun selectAll() { if (document.words.isNotEmpty()) select(0, document.words.lastIndex) }

    fun selectAt(x: Float, y: Float, continueDrag: Boolean = false) {
        val word = hitWord(x, y)
        if (word >= 0) { select(word, word); if (continueDrag) anchor = word }
    }

    private fun copy(text: String) {
        if (text.isBlank()) return
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.action_recognize_text), text))
        Ui.toast(context, context.getString(R.string.ocr_copied))
    }

    private fun select(start: Int, end: Int) {
        first = minOf(start, end)
        last = maxOf(start, end)
        invalidate()
        accessibility.invalidateRoot()
        positionToolbar()
    }

    fun wordBounds(index: Int): RectF { updateGeometry(); return RectF(bounds[index]) }

    private fun updateGeometry() {
        if (closed) return
        val matrix = normalizedToView()
        if (lastMatrix == matrix && outlines.size == document.words.size) return
        lastMatrix = Matrix(matrix)
        outlines.clear(); bounds.clear()
        for (word in document.words) {
            val points = word.corners.flatMap { listOf(it.x, it.y) }.toFloatArray()
            matrix.mapPoints(points)
            outlines.add(points)
            bounds.add(RectF(points.filterIndexed { i, _ -> i % 2 == 0 }.min(),
                points.filterIndexed { i, _ -> i % 2 == 1 }.min(),
                points.filterIndexed { i, _ -> i % 2 == 0 }.max(), points.filterIndexed { i, _ -> i % 2 == 1 }.max()))
        }
        invalidate()
        positionToolbar()
    }

    private fun positionToolbar() {
        val bar = toolbar ?: return
        if (width <= 0 || height <= 0) return
        copyButton?.apply { isEnabled = first >= 0; alpha = if (isEnabled) 1f else 0.42f }
        val margin = Ui.dp(context, 12)
        bar.measure(MeasureSpec.makeMeasureSpec((width - margin * 2).coerceAtLeast(1), MeasureSpec.AT_MOST),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.AT_MOST))
        val rect = if (first >= 0 && first < bounds.size) RectF(bounds[first]).apply {
            for (index in first..last) union(bounds[index])
        } else bounds.firstOrNull()?.let(::RectF) ?: RectF(width / 2f, height / 2f, width / 2f, height / 2f)
        val location = IntArray(2).also(::getLocationOnScreen)
        val bars = ViewCompat.getRootWindowInsets(this)?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        val top = maxOf(margin, (bars?.top ?: 0) - location[1] + margin)
        val bottom = (resources.displayMetrics.heightPixels - (bars?.bottom ?: 0) - location[1] - margin)
            .coerceAtMost(height - margin).coerceAtLeast(top)
        bar.x = (rect.centerX() - bar.measuredWidth / 2f).coerceIn(margin.toFloat(),
            (width - bar.measuredWidth - margin).coerceAtLeast(margin).toFloat())
        val above = rect.top - bar.measuredHeight - margin
        bar.y = (if (above >= top) above else rect.bottom + margin).coerceIn(top.toFloat(),
            (bottom - bar.measuredHeight).coerceAtLeast(top).toFloat())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateGeometry()
        outlines.forEachIndexed { index, points ->
            path.reset(); path.moveTo(points[0], points[1])
            for (corner in 1..3) path.lineTo(points[corner * 2], points[corner * 2 + 1])
            path.close()
            val selected = first >= 0 && index in first..last
            paint.style = if (selected) Paint.Style.FILL else Paint.Style.STROKE
            paint.color = if (selected) 0x66518DFF else 0x997B9DDB.toInt()
            paint.strokeWidth = resources.displayMetrics.density
            canvas.drawPath(path, paint)
        }
        if (first >= 0) {
            paint.style = Paint.Style.FILL; paint.color = blue
            val start = outlines[first]; val end = outlines[last]
            canvas.drawCircle(start[6], start[7], Ui.dp(context, 7).toFloat(), paint)
            canvas.drawCircle(end[4], end[5], Ui.dp(context, 7).toFloat(), paint)
        }
    }

    private fun hitWord(x: Float, y: Float, nearest: Boolean = false): Int {
        updateGeometry()
        val tolerance = if (nearest) Float.MAX_VALUE else Ui.dp(context, 10).toFloat()
        var distance = Float.MAX_VALUE
        var result = -1
        outlines.forEachIndexed { index, points -> if (OcrGeometry.contains(points, x, y)) return index }
        bounds.forEachIndexed { index, box ->
            val dx = maxOf(box.left - x, 0f, x - box.right)
            val dy = maxOf(box.top - y, 0f, y - box.bottom)
            val d = hypot(dx, dy)
            if (d < distance && d <= tolerance) { distance = d; result = index }
        }
        return result
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (closed) return false
        updateGeometry()
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && zoomTarget != null && !forwardingZoom) {
            forwardingZoom = true
            previousDown?.let { down -> zoomTarget.onTouchEvent(down) }
            anchor = -1
        }
        if (forwardingZoom) {
            zoomTarget?.onTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) forwardingZoom = false
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                previousDown?.recycle(); previousDown = MotionEvent.obtain(event)
                draggingHandle = if (first >= 0) {
                    val start = outlines[first]; val end = outlines[last]
                    when {
                        hypot(event.x - start[6], event.y - start[7]) < Ui.dp(context, 22) -> 1
                        hypot(event.x - end[4], event.y - end[5]) < Ui.dp(context, 22) -> 2
                        else -> 0
                    }
                } else 0
                anchor = when (draggingHandle) { 1 -> last; 2 -> first; else -> hitWord(event.x, event.y) }
                if (anchor >= 0 && draggingHandle == 0) select(anchor, anchor)
            }
            MotionEvent.ACTION_MOVE -> if (anchor >= 0) {
                val target = hitWord(event.x, event.y, nearest = true)
                if (target >= 0) select(anchor, target)
            }
            MotionEvent.ACTION_UP -> { performClick(); anchor = -1; previousDown?.recycle(); previousDown = null }
            MotionEvent.ACTION_CANCEL -> { anchor = -1; previousDown?.recycle(); previousDown = null }
        }
        return true
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean = accessibility.dispatchHoverEvent(event) || super.dispatchHoverEvent(event)
    override fun performClick(): Boolean = super.performClick()
    override fun onAttachedToWindow() { super.onAttachedToWindow(); viewTreeObserver.addOnPreDrawListener(preDraw) }
    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        close()
        super.onDetachedFromWindow()
    }
    fun close() {
        if (closed) return
        closed = true
        previousDown?.recycle(); previousDown = null
        viewTreeObserver.removeOnPreDrawListener(preDraw)
        toolbar?.let { (it.parent as? ViewGroup)?.removeView(it) }; toolbar = null
        copyButton = null
        (parent as? ViewGroup)?.removeView(this)
        onClosed()
    }
}
