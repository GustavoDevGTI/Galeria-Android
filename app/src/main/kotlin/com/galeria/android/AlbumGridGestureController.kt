package com.galeria.android

import kotlin.math.max
import kotlin.math.min

/** Gesture ownership and horizontal density accumulation only. RecyclerView,
 * refresh enabling, scrolling, adapter selection and reordering stay outside. */
internal class AlbumGridGestureController {
    enum class Route { SCROLL, PINCH, SELECTION, REORDER }

    var pinchActive = false
        private set
    private var pinchConsumed = false
    private var pinchScale = 1f
    private var previousSpan = 0f
    var selectionActive = false
        private set
    private var selectionPosition = NO_POSITION

    fun route(pointerDown: Boolean, pointers: Int, listMode: Boolean, reordering: Boolean): Route = when {
        pinchActive || pinchConsumed || (!listMode && !reordering && pointerDown && pointers > 1) -> Route.PINCH
        selectionActive -> Route.SELECTION
        reordering -> Route.REORDER
        else -> Route.SCROLL
    }

    fun beginSelection(position: Int) {
        selectionActive = true
        selectionPosition = position
    }

    fun endSelection() {
        selectionActive = false
        selectionPosition = NO_POSITION
    }

    fun selectThrough(target: Int, select: (Int) -> Boolean): Boolean {
        if (!selectionActive || target == NO_POSITION || target == selectionPosition) return false
        val start = min(selectionPosition, target).coerceAtLeast(0)
        val end = max(selectionPosition, target)
        var changed = false
        for (position in start..end) changed = select(position) || changed
        selectionPosition = target
        return changed
    }

    fun beginPinch(span: Float) {
        endSelection()
        pinchScale = 1f
        previousSpan = span
        pinchActive = true
        pinchConsumed = true
    }

    fun movePinch(span: Float): Int {
        if (!pinchActive) return 0
        var delta = 0
        if (previousSpan > 0f && span > 0f) {
            val factor = span / previousSpan
            if (factor.isFinite() && factor in 0.5f..2f) {
                pinchScale *= factor
                delta = GridColumnRules.columnDelta(pinchScale)
                if (delta != 0) pinchScale = 1f
            }
        }
        previousSpan = span
        return delta
    }

    /** Lifting one pointer stops scaling, but must not give the remaining
     * finger to pull-to-refresh or selection before the stream ends. */
    fun pointerUp() {
        pinchActive = false
        pinchScale = 1f
        previousSpan = 0f
    }

    fun endPinch() {
        pointerUp()
        pinchConsumed = false
    }

    companion object {
        private const val NO_POSITION = -1 // RecyclerView's adapter sentinel.
    }
}
