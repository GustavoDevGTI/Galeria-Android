package com.galeria.android

import android.content.Context
import androidx.recyclerview.widget.RecyclerView
import com.github.panpf.zoomimage.CoilZoomImageView

class AccessibleRecyclerView(context: Context) : RecyclerView(context) {
    override fun performClick(): Boolean {
        return super.performClick()
    }
}

class AccessibleCoilZoomImageView(context: Context) : CoilZoomImageView(context) {
    var suppressAccessibilityClickAction: Boolean = false
    var accessibilityClickAction: (() -> Unit)? = null

    init {
        isClickable = true
    }

    override fun performClick(): Boolean {
        val handled = super.performClick()
        if (!suppressAccessibilityClickAction) accessibilityClickAction?.invoke()
        return handled || accessibilityClickAction != null
    }
}
