package com.galeria.android

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView

/**
 * Dates while scrolling an album: a balloon next to the finger on the fast scroller, the years
 * marked along it, and the year of the first visible row at the top while the grid moves.
 */
internal fun AlbumMediaActivity.installScrollDates(content: FrameLayout) {
    val overlay = ScrollDateOverlay(this)
    scrollDateOverlay = overlay
    content.addView(overlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    fastScroller.onDragPosition = { position, centerY ->
        val item = adapter.peekItem(position)
        overlay.showBubble(item?.let { ScrollDateRules.balloon(it, mediaSortMode) },
            fastScroller.top + centerY, fastScroller.left.toFloat())
        if (item != null && ScrollDateRules.followsDate(mediaSortMode)) {
            overlay.showTop(ScrollDateRules.year(item.dateAdded)?.toString())
        }
    }
    grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
            if (dy == 0 || layoutManager.orientation != RecyclerView.VERTICAL) return
            if (!ScrollDateRules.followsDate(mediaSortMode)) return
            val first = layoutManager.findFirstVisibleItemPosition()
            if (first == RecyclerView.NO_POSITION) return
            adapter.peekItem(first)?.let { overlay.showTop(ScrollDateRules.year(it.dateAdded)?.toString()) }
        }
    })
}

/** Drag on the fast scroller started or ended: years along the bar while it lasts. */
internal fun AlbumMediaActivity.onScrollDateDrag(active: Boolean) {
    val overlay = scrollDateOverlay ?: return
    if (!active) {
        overlay.hideScrubbing()
        return
    }
    if (!ScrollDateRules.followsDate(mediaSortMode)) return
    val total = adapter.getCount()
    val marks = ScrollDateRules.yearMarks(total) { position ->
        adapter.peekItem(position)?.let { ScrollDateRules.year(it.dateAdded) }
    }
    overlay.setMarks(marks.map { (position, year) ->
        (fastScroller.top + fastScroller.centerYFor(position, total)) to year.toString()
    }, fastScroller.left.toFloat())
}
