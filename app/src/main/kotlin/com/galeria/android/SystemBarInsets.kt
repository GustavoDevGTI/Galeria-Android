package com.galeria.android

import android.app.Activity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.max

/**
 * Status and navigation bar sizes as the system reports them for this window, instead of reading
 * Android's internal dimensions (which do not account for cutouts or gesture navigation). The sizes
 * ignore whether the bars are shown, so hiding them does not move the screen. Until the window
 * reports its sizes the usual defaults are used, and [onChange] lays the screen out again.
 */
internal class SystemBarInsets(activity: Activity) {
    var top = Ui.dp(activity, 24)
        private set
    var bottom = Ui.dp(activity, 24)
        private set
    /** Navigation bar on the side, in landscape with button navigation. */
    var side = 0
        private set

    private val listeners = ArrayList<() -> Unit>()

    init {
        ViewCompat.getRootWindowInsets(activity.window.decorView)?.let(::read)
        ViewCompat.setOnApplyWindowInsetsListener(activity.window.decorView) { view, insets ->
            if (read(insets)) listeners.forEach { it() }
            ViewCompat.onApplyWindowInsets(view, insets)
        }
    }

    /** Runs [block] now and again whenever the bar sizes change. */
    fun onChange(block: () -> Unit) {
        listeners += block
        block()
    }

    private fun read(insets: WindowInsetsCompat): Boolean {
        val status = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
        val navigation = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars())
        val next = Triple(status.top, navigation.bottom, max(navigation.left, navigation.right))
        if (next == Triple(top, bottom, side)) return false
        top = next.first
        bottom = next.second
        side = next.third
        return true
    }
}
