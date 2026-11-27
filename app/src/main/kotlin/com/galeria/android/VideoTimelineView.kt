package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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

/**
 * Opens with both ends of the video visible. Closer levels scroll the filmstrip under a fixed
 * playhead. Taps zoom around the chosen frame; the two handles mark a section for playback.
 */
internal class VideoTimelineView(context: Context) : View(context) {
    enum class ExpansionState { CLOSED, LOADING, OPEN, FAILED }
    var onExpansionStateChanged: ((ExpansionState) -> Unit)? = null
    var expansionState = ExpansionState.CLOSED
        private set
    private val loadingTimeout = Runnable {
        if (expansionState == ExpansionState.LOADING) {
            expansionRequested = false
            visibility = GONE
            setExpansionState(ExpansionState.FAILED)
        }
    }
    var onScrubStart: (() -> Unit)? = null
    var onScrubMove: ((Long) -> Unit)? = null
    var onScrubStop: ((Long, Boolean) -> Unit)? = null
    var onRangeChanged: ((LongRange?) -> Unit)? = null
    var onZoomLevelChanged: (() -> Unit)? = null
    var durationMs = 0L
        private set
    var positionMs = 0L
        private set
    var isScrubbing = false
        private set
    private var source: VideoTimelineFrames? = null
    private var sourceIdentity: String? = null
    private var sourceUri: Uri? = null
    private var sourceRevision = ""
    private var downX = 0f
    private var downPosition = 0L
    private var moved = false
    private var samples = emptyList<Long>()
    private val frames = HashMap<Long, Bitmap>()
    private val failedFrames = HashSet<Long>()
    private var expansionRequested = false
    private var preparationEnabled = true
    private var backgroundPreparationReady = false
    private val unavailableFrame by lazy { context.getDrawable(R.drawable.ic_movie) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val srcRect = Rect()
    private val dstRect = RectF()
    private var range: LongRange? = null
    internal val selectedRange: LongRange? get() = range
    private var rangeBefore: LongRange? = null
    private var handleDragging: Boolean? = null
    private val clipPath = Path()
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat()
    }
    private val gripLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF101214.toInt(); strokeWidth = dp(2).toFloat(); strokeCap = Paint.Cap.ROUND
    }
    private val cellWidth get() = dp(54).toFloat()
    /** 0 overview of the whole video, 1 one second per picture, 2 frame by frame. */
    var zoomLevel = 0
        private set
    internal val intervalMs: Long
        get() = VideoTimelineRules.zoomInterval(zoomLevel, durationMs, viewportWidth(), cellWidth).coerceAtLeast(1L)

    /** Brings the strip closer or back to the overview; the pictures of the new scale load around the playhead. */
    fun setZoomLevel(level: Int) {
        val next = level.coerceIn(0, VideoTimelineRules.ZOOM_LEVELS - 1)
        if (next == zoomLevel) return
        zoomLevel = next
        frames.clear()
        failedFrames.clear()
        samples = emptyList()
        requestFrames()
        invalidate()
        updateGestureExclusion()
        onZoomLevelChanged?.invoke()
    }
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
        sourceUri = uri
        sourceRevision = revision
        frames.clear()
        failedFrames.clear()
        samples = emptyList()
        durationMs = 0L
        backgroundPreparationReady = false
        positionMs = 0L
        range = null
        zoomLevel = 0
        updateGestureExclusion()
        if (expansionRequested) { animate().cancel(); alpha = 1f; visibility = GONE }
        reportExpansionState()
        invalidate()
    }

    fun update(position: Long, duration: Long, prepareInBackground: Boolean = true) {
        backgroundPreparationReady = prepareInBackground
        durationMs = duration.coerceAtLeast(0L)
        if (!isScrubbing) positionMs = position.coerceIn(0L, durationMs)
        updateGestureExclusion()
        requestFrames()
        invalidate()
    }

    fun setSelectedRange(selected: LongRange?) {
        range = selected?.let { VideoTimelineRules.selectedRange(it.first, it.last, durationMs) }
        onRangeChanged?.invoke(range)
        updateGestureExclusion()
        invalidate()
    }

    /** Preparing the viewport starts with playback; opening never reveals loading cells. */
    fun setExpanded(expanded: Boolean) {
        // Ignore repeated requests while the initial viewport is already queued.
        if (expanded && expansionRequested && expansionState == ExpansionState.LOADING) return
        if (expanded && expansionState == ExpansionState.FAILED) {
            source?.close()
            source = sourceUri?.let { VideoTimelineFrames(context, it, sourceRevision) }
            failedFrames.clear()
            samples = emptyList()
        }
        expansionRequested = expanded
        if (!expanded) {
            // Closed, the strip opens again as the overview.
            if (zoomLevel != 0) { zoomLevel = 0; frames.clear(); failedFrames.clear(); samples = emptyList() }
            cancelGesture()
            animate().cancel()
            alpha = 1f
            visibility = GONE
        } else {
            if (!isPrepared) { animate().cancel(); alpha = 1f; visibility = GONE }
            requestFrames()
            revealIfPrepared()
        }
        reportExpansionState()
    }

    fun setPreparationEnabled(enabled: Boolean) {
        preparationEnabled = enabled
        if (enabled) requestFrames() else {
            source?.request(emptyList()) { _, _ -> }
            samples = emptyList()
        }
        reportExpansionState()
    }

    private fun revealIfPrepared() {
        if (!expansionRequested || !preparationEnabled || visibility == VISIBLE || !isPrepared) return
        if (visibleTimes(viewportWidth()).none { it in frames }) {
            expansionRequested = false
            setExpansionState(ExpansionState.FAILED)
            return
        }
        visibility = VISIBLE
        setExpansionState(ExpansionState.OPEN)
        alpha = 0f
        animate().alpha(1f).setDuration(120L).start()
    }

    private fun reportExpansionState() {
        if (!expansionRequested && expansionState == ExpansionState.FAILED) return
        setExpansionState(when {
            !expansionRequested || !preparationEnabled -> ExpansionState.CLOSED
            visibility == VISIBLE && isPrepared -> ExpansionState.OPEN
            else -> ExpansionState.LOADING
        })
    }

    private fun setExpansionState(state: ExpansionState) {
        if (state == expansionState) return
        expansionState = state
        removeCallbacks(loadingTimeout)
        if (state == ExpansionState.LOADING) postDelayed(loadingTimeout, 15_000L)
        onExpansionStateChanged?.invoke(state)
    }

    private fun viewportWidth(): Int {
        val container = parent as? View
        val available = container?.let { it.width - it.paddingLeft - it.paddingRight } ?: 0
        return available.takeIf { it > 0 } ?: width.takeIf { it > 0 } ?:
            (resources.displayMetrics.widthPixels - dp(32)).coerceAtLeast(1)
    }

    private fun viewportPosition() = if (zoomLevel == 0) durationMs / 2L
        else if (isScrubbing && handleDragging != null) downPosition else positionMs

    private fun pixelsPerMs(viewport: Int = viewportWidth()): Double = if (zoomLevel == 0 && durationMs > 0L)
        (viewport - dp(36)).coerceAtLeast(1).toDouble() / durationMs else cellWidth.toDouble() / intervalMs

    private fun visibleIndices(viewport: Int): LongRange = VideoTimelineRules.visibleIndices(
        viewportPosition(), durationMs, viewport, (intervalMs * pixelsPerMs(viewport)).toFloat(), intervalMs)

    private fun visibleTimes(viewport: Int): List<Long> = visibleIndices(viewport)
        .filter { it * intervalMs < durationMs }
        .map { VideoTimelineRules.frameTime(it, durationMs, intervalMs) }.distinct()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        updateGestureExclusion()
        requestFrames()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // View can invoke this during construction, before the fields are initialized.
        if (visibility == VISIBLE) post { requestFrames() }
        else if (source != null) cancelGesture()
    }

    private fun requestFrames() {
        if (!preparationEnabled || !isAttachedToWindow || windowVisibility != VISIBLE || durationMs <= 0L) return
        // Opening is explicit. Closed, thumbnail decoding waits for the player's first ready state.
        if (!backgroundPreparationReady && !expansionRequested) return
        val indices = visibleIndices(viewportWidth())
        // A few seconds ahead keep the closed strip ready as playback advances.
        val last = (indices.last + 3L).coerceAtMost(VideoTimelineRules.frameCount(durationMs, intervalMs) - 1L)
        val next = (indices.first..last).map { VideoTimelineRules.frameTime(it, durationMs, intervalMs) }.distinct()
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
        val left = xAt(0L).coerceAtLeast(0f)
        val right = xAt(durationMs).coerceAtMost(width.toFloat())
        if (right <= left || bottom <= top) return
        val corner = dp(6).toFloat()
        dstRect.set(left, top, right, bottom)
        clipPath.reset()
        clipPath.addRoundRect(dstRect, corner, corner, Path.Direction.CW)
        val saved = canvas.save()
        canvas.clipPath(clipPath)
        paint.color = 0xFF23272C.toInt()
        canvas.drawRect(dstRect, paint)
        for (index in visibleIndices(width)) {
            if (index * intervalMs >= durationMs) continue
            val time = VideoTimelineRules.frameTime(index, durationMs, intervalMs)
            dstRect.set(xAt(index * intervalMs), top, xAt(minOf((index + 1L) * intervalMs, durationMs)), bottom)
            if (dstRect.right < 0f || dstRect.left > width) continue
            val bitmap = frames[time]
            paint.color = 0xFF303237.toInt()
            canvas.drawRect(dstRect, paint)
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
        canvas.restoreToCount(saved)
        val start = xAt(range?.first ?: 0L)
        val end = xAt(range?.last ?: durationMs)
        dstRect.set(start, top, end, bottom)
        canvas.drawRoundRect(dstRect, corner, corner, outline)
        drawHandle(canvas, start, top, bottom, true)
        drawHandle(canvas, end, top, bottom, false)
        val x = xAt(positionMs)
        paint.color = Color.WHITE
        canvas.drawRoundRect(x - dp(1), top - dp(4), x + dp(1), bottom + dp(4), dp(1).toFloat(), dp(1).toFloat(), paint)
    }

    private fun xAt(time: Long): Float =
        (width / 2.0 + (time - viewportPosition()) * pixelsPerMs(width)).toFloat()

    private fun drawHandle(canvas: Canvas, edge: Float, top: Float, bottom: Float, start: Boolean) {
        if (edge < 0f || edge > width) return
        val left = if (start) edge - dp(12) else edge
        paint.color = Color.WHITE
        dstRect.set(left, top, left + dp(12), bottom)
        canvas.drawRoundRect(dstRect, dp(6).toFloat(), dp(6).toFloat(), paint)
        canvas.drawLine(dstRect.centerX(), top + (bottom - top) * 0.35f,
            dstRect.centerX(), bottom - (bottom - top) * 0.35f, gripLine)
    }

    private fun handleAt(x: Float): Boolean? {
        val start = xAt(range?.first ?: 0L)
        val end = xAt(range?.last ?: durationMs)
        val startDistance = abs(x - (start - dp(6)))
        val endDistance = abs(x - (end + dp(6)))
        val reach = dp(24)
        return when {
            start in 0f..width.toFloat() && startDistance <= reach && startDistance <= endDistance -> true
            end in 0f..width.toFloat() && endDistance <= reach -> false
            else -> null
        }
    }

    /** The handles overlap Android's back edges. Reserve only their 58dp-high touch regions. */
    private fun updateGestureExclusion() {
        if (Build.VERSION.SDK_INT < 29) return
        val exclusions = if (durationMs <= 0L || width <= 0 || height <= 0) emptyList() else
            listOf(xAt(range?.first ?: 0L), xAt(range?.last ?: durationMs))
                .filter { it in 0f..width.toFloat() }
                .map { edge -> Rect((edge.toInt() - dp(36)).coerceAtLeast(0), 0,
                    (edge.toInt() + dp(36)).coerceAtMost(width), height) }
        if (systemGestureExclusionRects != exclusions) systemGestureExclusionRects = exclusions
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || durationMs <= 0L || width <= 0) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                isScrubbing = true
                moved = false
                downX = event.x
                downPosition = positionMs
                rangeBefore = range
                handleDragging = handleAt(event.x)
                onScrubStart?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                if (!isScrubbing) return true
                if (abs(event.x - downX) > ViewConfiguration.get(context).scaledTouchSlop) moved = true
                if (moved) {
                    val edge = handleDragging
                    val delta = ((event.x - downX) / pixelsPerMs(width)).toLong()
                    if (edge != null) {
                        val before = if (edge) rangeBefore?.first ?: 0L else rangeBefore?.last ?: durationMs
                        range = VideoTimelineRules.moveRangeEdge(rangeBefore, edge, before + delta, durationMs)
                        choose(if (edge) range?.first ?: 0L else (range?.last ?: durationMs) - 1L)
                    } else choose((downPosition - delta).coerceIn(0L, durationMs))
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!isScrubbing) return true
                val tap = !moved && handleDragging == null
                if (tap) {
                    choose((viewportPosition() + ((event.x - width / 2f) / pixelsPerMs(width)).toLong()).coerceIn(0L, durationMs))
                }
                finishGesture(false)
                if (tap) {
                    setZoomLevel(zoomLevel + 1)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finishGesture(true)
        }
        return true
    }

    private fun choose(position: Long) {
        positionMs = position
        updateGestureExclusion()
        onScrubMove?.invoke(positionMs)
        requestFrames()
        invalidate()
    }

    private fun finishGesture(cancelled: Boolean) {
        if (!isScrubbing) return
        if (cancelled) range = rangeBefore
        else if (handleDragging != null && moved) onRangeChanged?.invoke(range)
        isScrubbing = false
        if (cancelled) positionMs = downPosition
        handleDragging = null
        updateGestureExclusion()
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
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.timeline_range_start, context.getString(R.string.timeline_range_start)))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.timeline_range_end, context.getString(R.string.timeline_range_end)))
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.timeline_range_reset, context.getString(R.string.timeline_range_reset)))
        if (zoomLevel < VideoTimelineRules.ZOOM_LEVELS - 1)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction(R.id.timeline_zoom, context.getString(R.string.video_timeline_closer)))
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        when (action) {
            R.id.timeline_range_start, R.id.timeline_range_end -> {
                if (durationMs <= 0L) return false
                setSelectedRange(VideoTimelineRules.moveRangeEdge(range, action == R.id.timeline_range_start, positionMs, durationMs))
                return true
            }
            R.id.timeline_range_reset -> { setSelectedRange(null); return true }
            R.id.timeline_zoom -> { setZoomLevel(zoomLevel + 1); return true }
        }
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
        removeCallbacks(loadingTimeout)
        expansionRequested = false
        setExpansionState(ExpansionState.CLOSED)
        cancelGesture()
        source?.close()
        source = null
        sourceIdentity = null
        sourceUri = null
        frames.clear()
        failedFrames.clear()
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = Ui.dp(context, value)
}
