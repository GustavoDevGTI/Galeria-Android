package com.galeria.android

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class GridSpacingItemDecoration(
    private val spacingPx: Int,
    private val spanCount: Int,
    private val horizontal: Boolean = false
) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val position = parent.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) {
            outRect.set(0, 0, 0, 0)
            return
        }

        if (spanCount <= 1) {
            outRect.set(spacingPx, spacingPx / 2, spacingPx, spacingPx / 2)
            return
        }

        // The span GridLayoutManager assigned, so rows after a full-width group title stay aligned.
        val params = view.layoutParams as? GridLayoutManager.LayoutParams
        if (params != null && params.spanSize >= spanCount) {
            if (horizontal) outRect.set(spacingPx, 0, 0, 0) else outRect.set(0, spacingPx, 0, 0)
            return
        }
        val column = params?.spanIndex?.takeIf { it >= 0 } ?: (position % spanCount)
        val start = spacingPx - column * spacingPx / spanCount
        val end = (column + 1) * spacingPx / spanCount
        if (horizontal) {
            // Rows instead of columns: the same spacing, turned sideways.
            outRect.set(spacingPx, start, 0, end)
        } else {
            outRect.set(start, spacingPx, end, 0)
        }
    }
}
