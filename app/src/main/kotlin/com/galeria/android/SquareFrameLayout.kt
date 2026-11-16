package com.galeria.android

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    /** In a horizontal grid the row height sets the size instead of the column width. */
    var squareByHeight = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (squareByHeight) super.onMeasure(heightMeasureSpec, heightMeasureSpec)
        else super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
