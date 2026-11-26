package com.galeria.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.OverScroller
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Pure geometry of the editing timeline: the playhead stays in the middle and the montage slides under it. */
internal object JoinTimelineRules {
    const val FRAME_STEP_MS = 250L

    fun xAt(timeMs: Long, positionMs: Long, width: Int, pxPerMs: Float): Float =
        width / 2f + (timeMs - positionMs) * pxPerMs

    fun timeAt(x: Float, positionMs: Long, width: Int, pxPerMs: Float): Long =
        positionMs + ((x - width / 2f) / pxPerMs).roundToLong()

    /** Frame times are rounded so neighbouring tiles share cached pictures. */
    fun frameTime(sourceMs: Long): Long = (sourceMs / FRAME_STEP_MS) * FRAME_STEP_MS
}

/**
 * The editing timeline: every kept part laid out by length, with pictures from the media and a
 * fixed playhead. On the selected video, one part is lit with a handle at each end: the handles
 * mark the area to keep (outside it darkens) and nothing changes until the scissors are pressed.
 */
internal class JoinTimelineView(context: Context) : View(context) {
    var onScrub: ((positionMs: Long, active: Boolean) -> Unit)? = null
    var onSelect: ((Int) -> Unit)? = null
    /** A handle of the marked area moved: the preview shows the frame at that edge. */
    var onTrimming: ((uri: String, sourceMs: Long) -> Unit)? = null
    /** A handle was let go, at [edgeMs] of the montage: the marked area changed. */
    var onWindowChanged: ((edgeMs: Long) -> Unit)? = null

    private var clips: List<JoinClip> = emptyList()
    private var photoMs = 5_000L
    private var segments: List<JoinSegment> = emptyList()
    private var totalMs = 0L
    private var windows: List<JoinWindow> = emptyList()
    private var selected = -1
    var positionMs = 0L
        private set

    private val density = resources.displayMetrics.density
    private var pxPerMs = 48f * density / 1000f
    private val minPxPerMs = 6f * density / 1000f
    private val maxPxPerMs = 320f * density / 1000f
    private val track = 60f * density
    private val tile = 40f * density
    private val handle = 12f * density
    private val corner = 6f * density
    private val seam = 2f * density

    private val thumbs = HashMap<String, Bitmap>()
    private val frames = HashMap<String, VideoTimelineFrames>()
    // Recently used pictures only; older ones fall back to the shared frame cache on disk.
    private val pictures = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > 240
    }

    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF23272C.toInt() }
    private val image = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Color.WHITE }
    private val thinOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * density; color = 0x99FFFFFF.toInt() }
    private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val gripLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF101214.toInt(); strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND }
    private val shade = Paint().apply { color = 0xB3101214.toInt() }
    private val head = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; strokeWidth = 2f * density; strokeCap = Paint.Cap.ROUND }
    private val path = Path()
    private val rect = RectF()
    private val matrix = Matrix()

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val scroller = OverScroller(context)
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var downPosition = 0L
    private var dragging = false
    private var flinging = false
    private var scaling = false

    // The lit part of the selected video and the area marked on it (source milliseconds).
    private var activeRange = -1
    private var windowStart = 0L
    private var windowEnd = 0L
    // A handle being dragged: which end, and where the area was when it started.
    private var handleDragging = false
    private var handleStart = false
    private var handleOriginal = 0L

    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { scaling = true; return true }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pxPerMs = (pxPerMs * detector.scaleFactor).coerceIn(minPxPerMs, maxPxPerMs)
            requestFrames()
            invalidate()
            return true
        }
    })

    init {
        isFocusable = true
        contentDescription = context.getString(R.string.join_timeline)
    }

    fun setClips(clips: List<JoinClip>, photoMs: Long, selected: Int, transitions: List<JoinTransition> = emptyList()) {
        val reselected = selected != this.selected
        this.clips = clips
        this.photoMs = photoMs
        this.selected = selected
        if (reselected) activeRange = -1
        segments = MediaJoinRules.segments(clips, photoMs, transitions)
        totalMs = MediaJoinRules.totalMs(clips, photoMs, transitions)
        windows = MediaJoinRules.windows(clips, photoMs, transitions)
        positionMs = positionMs.coerceIn(0L, totalMs)
        val uris = clips.filter { it.media.isVideo }.map { it.media.uri }.toSet()
        frames.keys.filter { it !in uris }.forEach { frames.remove(it)?.close() }
        uris.forEach { uri -> frames.getOrPut(uri) { VideoTimelineFrames(context, Uri.parse(uri), "join") } }
        // A part that changed (cut, split, removed) starts lit whole again.
        val range = activeRangeValue()
        if (range == null || windowStart < range.first || windowEnd > range.last || windowStart >= windowEnd) resetActive()
        requestFrames()
        invalidate()
    }

    fun setThumbs(thumbs: Map<String, Bitmap>) { this.thumbs.clear(); this.thumbs.putAll(thumbs); invalidate() }

    fun setSelected(index: Int) {
        if (index != selected || activeRangeValue() == null) { selected = index; resetActive() }
        invalidate()
    }

    /** The lit part of the selected video (its index among the video's parts), or null for a photo. */
    fun activePart(): Int? = activeRange.takeIf { activeRangeValue() != null }

    /** The area marked to keep, when it leaves something of the lit part out; null otherwise. */
    fun pendingWindow(): LongRange? {
        val range = activeRangeValue() ?: return null
        return (windowStart..windowEnd).takeIf { windowStart > range.first || windowEnd < range.last }
    }

    /** Marks an area on the lit part as the handles would (tests only drive it this way). */
    @androidx.annotation.VisibleForTesting
    internal fun markArea(startMs: Long, endMs: Long) {
        val range = activeRangeValue() ?: return
        windowStart = startMs.coerceIn(range.first, range.last - MediaJoinRules.MIN_PART_MS)
        windowEnd = endMs.coerceIn(windowStart + MediaJoinRules.MIN_PART_MS, range.last)
        invalidate()
    }

    /** Lights one piece of the selected video, as tapping it does. */
    @androidx.annotation.VisibleForTesting
    internal fun lightPiece(range: Int) { activate(range); invalidate() }

    private fun activeRangeValue(): LongRange? =
        clips.getOrNull(selected)?.takeIf { it.media.isVideo }?.ranges?.getOrNull(activeRange)

    /** Lights the part of the selected video under the playhead (else its first part), whole. */
    private fun resetActive() {
        val clip = clips.getOrNull(selected)?.takeIf { it.media.isVideo }
        if (clip == null) { activeRange = -1; return }
        val own = segments.withIndex().filter { it.value.clipIndex == selected }
        val under = own.firstOrNull { positionMs in it.value.startMs until it.value.endMs }
        activate(own.indexOf(under).coerceAtLeast(0))
    }

    private fun activate(range: Int) {
        activeRange = range
        val value = activeRangeValue() ?: return
        windowStart = value.first
        windowEnd = value.last
    }

    /** Follows playback; ignored while the user is holding the timeline. */
    fun setPosition(ms: Long) {
        if (dragging || handleDragging || flinging) return
        positionMs = ms.coerceIn(0L, totalMs)
        requestFrames()
        invalidate()
    }

    fun release() { frames.values.forEach { it.close() }; frames.clear() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (track + 24f * density).roundToInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { requestFrames() }

    private fun x(timeMs: Long) = JoinTimelineRules.xAt(timeMs, positionMs, width, pxPerMs)

    private fun requestFrames() {
        if (width == 0) return
        val tileMs = (tile / pxPerMs).toLong().coerceAtLeast(1L)
        val wanted = HashMap<String, MutableList<Long>>()
        segments.forEach { segment ->
            val media = clips[segment.clipIndex].media
            if (!media.isVideo) return@forEach
            var at = segment.startMs
            while (at < segment.endMs) {
                val left = x(at)
                if (left > width) break
                if (left + tile >= 0f) wanted.getOrPut(media.uri) { ArrayList() } += JoinTimelineRules.frameTime(segment.sourceStartMs + (at - segment.startMs))
                at += tileMs
            }
        }
        frames.forEach { (uri, loader) ->
            val times = wanted[uri].orEmpty().filter { "$uri|$it" !in pictures }
            loader.request(times) { time, bitmap -> pictures["$uri|$time"] = bitmap; invalidate() }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val top = 12f * density
        val bottom = top + track
        val tileMs = (tile / pxPerMs).toLong().coerceAtLeast(1L)
        segments.forEachIndexed { index, segment ->
            val left = x(segment.startMs) + seam / 2f
            val right = x(segment.endMs) - seam / 2f
            if (right < 0f || left > width) return@forEachIndexed
            val media = clips[segment.clipIndex].media
            rect.set(left, top, right, bottom)
            path.reset()
            path.addRoundRect(rect, corner, corner, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRect(rect, base)
            var at = segment.startMs
            while (at < segment.endMs) {
                val tileLeft = x(at)
                if (tileLeft > width) break
                if (tileLeft + tile >= 0f) {
                    val bitmap = if (media.isVideo) nearestFrame(media.uri, JoinTimelineRules.frameTime(segment.sourceStartMs + (at - segment.startMs)))
                    else thumbs[media.uri]
                    bitmap?.let { drawTile(canvas, it, tileLeft, top) }
                }
                at += tileMs
            }
            if (segment.clipIndex == selected && rangeIndexOf(index) == activeRange) drawOutsideShade(canvas, segment, top, bottom)
            canvas.restore()
        }
        // Each transition shows as a small mark over the stretch where the two media overlap.
        windows.forEach { window ->
            val left = x(window.startMs)
            val right = x(window.endMs)
            if (right < 0f || left > width) return@forEach
            rect.set(left, top - 3f * density, right, top + 3f * density)
            canvas.drawRoundRect(rect, 3f * density, 3f * density, grip)
        }
        if (selected in clips.indices) drawSelection(canvas, top, bottom)
        val center = width / 2f
        canvas.drawLine(center, 4f * density, center, height - 4f * density, head)
    }

    private fun nearestFrame(uri: String, time: Long): Bitmap? =
        pictures["$uri|$time"] ?: pictures.entries.filter { it.key.startsWith("$uri|") }
            .minByOrNull { abs(it.key.substringAfter('|').toLong() - time) }?.value

    private fun drawTile(canvas: Canvas, bitmap: Bitmap, left: Float, top: Float) {
        val s = maxOf(tile / bitmap.width, track / bitmap.height)
        matrix.setScale(s, s)
        matrix.postTranslate(left + (tile - bitmap.width * s) / 2f, top + (track - bitmap.height * s) / 2f)
        canvas.save()
        canvas.clipRect(left, top, left + tile, top + track)
        canvas.drawBitmap(bitmap, matrix, image)
        canvas.restore()
    }

    /** Montage time of [sourceMs] inside [segment]. */
    private fun timeOf(segment: JoinSegment, sourceMs: Long) = segment.startMs + (sourceMs - segment.sourceStartMs)

    /** Outside the marked area the lit part is darkened: that is what the scissors will take away. */
    private fun drawOutsideShade(canvas: Canvas, segment: JoinSegment, top: Float, bottom: Float) {
        val keepLeft = x(timeOf(segment, windowStart))
        val keepRight = x(timeOf(segment, windowEnd))
        if (windowStart > segment.sourceStartMs) canvas.drawRect(x(segment.startMs), top, keepLeft, bottom, shade)
        if (windowEnd < segment.sourceEndMs) canvas.drawRect(keepRight, top, x(segment.endMs), bottom, shade)
    }

    private fun rangeIndexOf(segmentIndex: Int): Int {
        val clip = segments[segmentIndex].clipIndex
        return segments.subList(0, segmentIndex).count { it.clipIndex == clip }
    }

    private fun drawSelection(canvas: Canvas, top: Float, bottom: Float) {
        segments.forEachIndexed { index, segment ->
            if (segment.clipIndex != selected) return@forEachIndexed
            val lit = clips[selected].media.isVideo && rangeIndexOf(index) == activeRange
            // The lit part is outlined around the marked area, with a handle at each end; the
            // other parts of the same video get a thin outline.
            val left = (if (lit) x(timeOf(segment, windowStart)) else x(segment.startMs)) + seam / 2f
            val right = (if (lit) x(timeOf(segment, windowEnd)) else x(segment.endMs)) - seam / 2f
            rect.set(left, top, right, bottom)
            canvas.drawRoundRect(rect, corner, corner, if (lit || !clips[selected].media.isVideo) outline else thinOutline)
            if (lit) {
                drawHandle(canvas, left, top, bottom, true)
                drawHandle(canvas, right, top, bottom, false)
            }
        }
    }

    private fun drawHandle(canvas: Canvas, edge: Float, top: Float, bottom: Float, start: Boolean) {
        val left = if (start) edge - handle else edge
        rect.set(left, top, left + handle, bottom)
        canvas.drawRoundRect(rect, corner, corner, grip)
        val middle = left + handle / 2f
        canvas.drawLine(middle, top + track * 0.35f, middle, bottom - track * 0.35f, gripLine)
    }

    /** The lit part's segment on the timeline, if any. */
    private fun activeSegment(): JoinSegment? {
        if (activeRangeValue() == null) return null
        return segments.withIndex().filter { it.value.clipIndex == selected }.getOrNull(activeRange)?.value
    }

    /** The handle of the marked area under [x]: true for its start, false for its end. */
    private fun handleAt(x: Float): Boolean? {
        val segment = activeSegment() ?: return null
        val reach = handle + 8f * density
        val startDistance = abs(x - (x(timeOf(segment, windowStart)) - handle / 2f))
        val endDistance = abs(x - (x(timeOf(segment, windowEnd)) + handle / 2f))
        return when {
            startDistance < reach && startDistance <= endDistance -> true
            endDistance < reach -> false
            else -> null
        }
    }

    /** Moves one end of the marked area, kept inside the lit part and at least [MediaJoinRules.MIN_PART_MS] long. */
    private fun moveHandle(sourceMs: Long) {
        val range = activeRangeValue() ?: return
        if (handleStart) windowStart = sourceMs.coerceIn(range.first, windowEnd - MediaJoinRules.MIN_PART_MS)
        else windowEnd = sourceMs.coerceIn(windowStart + MediaJoinRules.MIN_PART_MS, range.last)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        if (velocity == null) velocity = VelocityTracker.obtain()
        velocity?.addMovement(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                flinging = false
                scaling = false
                downX = event.x
                downPosition = positionMs
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(true)
                handleAt(event.x)?.let { start ->
                    handleDragging = true
                    handleStart = start
                    handleOriginal = if (start) windowStart else windowEnd
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaling || event.pointerCount > 1) return true
                if (handleDragging) {
                    moveHandle(handleOriginal + ((event.x - downX) / pxPerMs).roundToLong())
                    onTrimming?.invoke(clips[selected].media.uri, if (handleStart) windowStart else windowEnd)
                    invalidate()
                    return true
                }
                if (!dragging && abs(event.x - downX) > touchSlop) dragging = true
                if (dragging) {
                    positionMs = (downPosition - ((event.x - downX) / pxPerMs).roundToLong()).coerceIn(0L, totalMs)
                    onScrub?.invoke(positionMs, true)
                    requestFrames()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (handleDragging) {
                    handleDragging = false
                    // Nothing is cut yet: the area stays marked until the scissors.
                    activeSegment()?.let { onWindowChanged?.invoke(timeOf(it, if (handleStart) windowStart else windowEnd)) }
                    invalidate()
                } else if (dragging) {
                    velocity?.computeCurrentVelocity(1000)
                    val speed = -(velocity?.xVelocity ?: 0f)
                    if (abs(speed) > ViewConfiguration.get(context).scaledMinimumFlingVelocity) {
                        flinging = true
                        scroller.fling((positionMs * pxPerMs).roundToInt(), 0, speed.roundToInt(), 0, 0, (totalMs * pxPerMs).roundToInt(), 0, 0)
                        postInvalidateOnAnimation()
                    } else {
                        onScrub?.invoke(positionMs, false)
                    }
                } else if (!scaling) {
                    performClick()
                    val time = JoinTimelineRules.timeAt(event.x, positionMs, width, pxPerMs)
                    val tapped = segments.indexOfFirst { time in it.startMs until it.endMs }
                    if (tapped >= 0) {
                        val segment = segments[tapped]
                        onSelect?.invoke(segment.clipIndex)
                        // Tapping one piece of a video cut in pieces lights that piece.
                        if (segment.clipIndex == selected && clips[selected].media.isVideo) {
                            activate(rangeIndexOf(tapped))
                            onWindowChanged?.invoke(segment.startMs)
                            invalidate()
                        }
                    }
                }
                dragging = false
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                handleDragging = false
                if (dragging) onScrub?.invoke(positionMs, false)
                dragging = false
                velocity?.recycle(); velocity = null
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun computeScroll() {
        if (!flinging) return
        if (scroller.computeScrollOffset()) {
            positionMs = (scroller.currX / pxPerMs).roundToLong().coerceIn(0L, totalMs)
            onScrub?.invoke(positionMs, true)
            requestFrames()
            postInvalidateOnAnimation()
        } else {
            flinging = false
            onScrub?.invoke(positionMs, false)
        }
    }
}
