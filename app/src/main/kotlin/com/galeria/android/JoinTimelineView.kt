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
 * The editing timeline: every kept part laid out by length, with pictures from the media, a fixed
 * playhead and handles on the selected media to cut its start, end or an inner edge.
 */
internal class JoinTimelineView(context: Context) : View(context) {
    var onScrub: ((positionMs: Long, active: Boolean) -> Unit)? = null
    var onSelect: ((Int) -> Unit)? = null
    var onTrim: ((clip: Int, range: Int, start: Boolean, sourceMs: Long) -> Unit)? = null
    var onTrimming: ((uri: String, sourceMs: Long) -> Unit)? = null

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

    // A handle being dragged: the model only changes when it is let go.
    private var trimClip = -1
    private var trimRange = -1
    private var trimStart = false
    private var trimOriginal = 0L
    private var trimValue = 0L

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
        this.clips = clips
        this.photoMs = photoMs
        this.selected = selected
        segments = MediaJoinRules.segments(clips, photoMs, transitions)
        totalMs = MediaJoinRules.totalMs(clips, photoMs, transitions)
        windows = MediaJoinRules.windows(clips, photoMs, transitions)
        positionMs = positionMs.coerceIn(0L, totalMs)
        val uris = clips.filter { it.media.isVideo }.map { it.media.uri }.toSet()
        frames.keys.filter { it !in uris }.forEach { frames.remove(it)?.close() }
        uris.forEach { uri -> frames.getOrPut(uri) { VideoTimelineFrames(context, Uri.parse(uri), "join") } }
        requestFrames()
        invalidate()
    }

    fun setThumbs(thumbs: Map<String, Bitmap>) { this.thumbs.clear(); this.thumbs.putAll(thumbs); invalidate() }

    fun setSelected(index: Int) { selected = index; invalidate() }

    /** Follows playback; ignored while the user is holding the timeline. */
    fun setPosition(ms: Long) {
        if (dragging || trimClip >= 0 || flinging) return
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
            if (trimClip == segment.clipIndex && trimRange == rangeIndexOf(index)) drawTrimShade(canvas, segment, top, bottom)
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

    /** While a handle is held, the part that will be cut is darkened instead of removed. */
    private fun drawTrimShade(canvas: Canvas, segment: JoinSegment, top: Float, bottom: Float) {
        val delta = trimValue - trimOriginal
        if (trimStart && delta > 0) canvas.drawRect(x(segment.startMs), top, x(segment.startMs + delta), bottom, shade)
        if (!trimStart && delta < 0) canvas.drawRect(x(segment.endMs + delta), top, x(segment.endMs), bottom, shade)
    }

    private fun rangeIndexOf(segmentIndex: Int): Int {
        val clip = segments[segmentIndex].clipIndex
        return segments.subList(0, segmentIndex).count { it.clipIndex == clip }
    }

    private fun drawSelection(canvas: Canvas, top: Float, bottom: Float) {
        segments.forEachIndexed { index, segment ->
            if (segment.clipIndex != selected) return@forEachIndexed
            val range = rangeIndexOf(index)
            var left = x(segment.startMs) + seam / 2f
            var right = x(segment.endMs) - seam / 2f
            if (trimClip == selected && trimRange == range) {
                val delta = (trimValue - trimOriginal) * pxPerMs
                if (trimStart) left += delta else right += delta
            }
            rect.set(left, top, right, bottom)
            canvas.drawRoundRect(rect, corner, corner, outline)
            if (clips[selected].media.isVideo) {
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

    /** The handle under [x], as (segment index, start?), on the selected video only. */
    private fun handleAt(x: Float): Pair<Int, Boolean>? {
        if (selected !in clips.indices || !clips[selected].media.isVideo) return null
        val reach = handle + 8f * density
        var best: Pair<Int, Boolean>? = null
        var bestDistance = Float.MAX_VALUE
        segments.forEachIndexed { index, segment ->
            if (segment.clipIndex != selected) return@forEachIndexed
            val startEdge = x(segment.startMs) - handle / 2f
            val endEdge = x(segment.endMs) + handle / 2f
            listOf(startEdge to true, endEdge to false).forEach { (edge, start) ->
                val distance = abs(x - edge)
                if (distance < reach && distance < bestDistance) { bestDistance = distance; best = index to start }
            }
        }
        return best
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
                handleAt(event.x)?.let { (segmentIndex, start) ->
                    val segment = segments[segmentIndex]
                    trimClip = segment.clipIndex
                    trimRange = rangeIndexOf(segmentIndex)
                    trimStart = start
                    trimOriginal = if (start) segment.sourceStartMs else segment.sourceEndMs
                    trimValue = trimOriginal
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (scaling || event.pointerCount > 1) return true
                if (trimClip >= 0) {
                    val proposed = trimOriginal + ((event.x - downX) / pxPerMs).roundToLong()
                    val clip = MediaJoinRules.trimmed(clips[trimClip], trimRange, trimStart, proposed)
                    trimValue = clip.ranges[trimRange].let { if (trimStart) it.first else it.last }
                    onTrimming?.invoke(clips[trimClip].media.uri, trimValue)
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
                if (trimClip >= 0) {
                    val clip = trimClip
                    trimClip = -1
                    if (trimValue != trimOriginal) onTrim?.invoke(clip, trimRange, trimStart, trimValue)
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
                    segments.firstOrNull { time in it.startMs until it.endMs }?.let { onSelect?.invoke(it.clipIndex) }
                }
                dragging = false
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                trimClip = -1
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
