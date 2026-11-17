package com.galeria.android

import android.animation.ValueAnimator
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.min

/** The albums as the main screen last showed them, in the same order, for the album sidebar. */
internal object AlbumSidebarSource {
    @Volatile var albums: List<AlbumItem> = emptyList()
}

/** When a swipe inside the album grid opens the album sidebar. */
internal object AlbumSidebarRules {
    /** A move to the right, mostly horizontal, past the touch slop: the panel starts following the finger. */
    fun startsDrag(dx: Float, dy: Float, slopPx: Float): Boolean = dx > slopPx * 1.5f && dx > abs(dy) * 1.4f

    /** On release the panel stays open past a third of the way, or after a quick swipe to the right. */
    fun settlesOpen(progress: Float, velocityX: Float, flingPx: Float): Boolean =
        velocityX > flingPx || (progress >= 0.35f && velocityX > -flingPx)

    /** A vertical move first means the user is scrolling the grid. */
    fun isScroll(dx: Float, dy: Float, slopPx: Float): Boolean = abs(dy) > slopPx * 2 && abs(dy) > abs(dx)
}

/**
 * Left panel over the album grid with every album, one per row, in the same look as the "Mover"
 * panel. Tapping an album opens it in place of the current one.
 */
internal class AlbumSidebar(
    private val activity: AlbumMediaActivity,
    private val currentKey: String?,
    private val onPick: (AlbumItem) -> Unit
) {
    private var overlay: FrameLayout? = null
    private var panel: LinearLayout? = null
    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = dismiss()
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, backCallback)
    }

    val isShowing: Boolean get() = overlay != null

    /** How open the panel is, from 0 (hidden) to 1 (fully open). */
    private var progress = 0f
    private var sheetWidth = 0

    fun show(albums: List<AlbumItem>) {
        if (!attach(albums)) return
        Haptics.perform(activity, Haptics.Effect.TICK)
        animate(0f, 1f, sheetWidth) {}
    }

    /** A swipe started: the panel appears at the edge and then follows the finger. */
    fun beginDrag(albums: List<AlbumItem>): Boolean {
        if (!attach(albums)) return false
        Haptics.perform(activity, Haptics.Effect.TICK)
        return true
    }

    /** The finger is [distance] pixels to the right of where the drag took over. */
    fun dragTo(distance: Float) {
        if (overlay == null || sheetWidth <= 0) return
        setProgress((distance / sheetWidth).coerceIn(0f, 1f))
    }

    /** Released: opens past a third of the way or with a quick swipe to the right, otherwise slides back. */
    fun endDrag(velocityX: Float) {
        if (overlay == null) return
        if (AlbumSidebarRules.settlesOpen(progress, velocityX, dp(FLING_DP_PER_SECOND).toFloat())) {
            animate(progress, 1f, sheetWidth) {}
        } else {
            dismiss()
        }
    }

    private fun setProgress(value: Float) {
        progress = value
        val sheet = panel ?: return
        sheet.translationX = -sheetWidth * (1f - value)
        (sheet.parent as? ViewGroup)?.getChildAt(0)?.alpha = value
    }

    private fun attach(albums: List<AlbumItem>): Boolean {
        if (overlay != null || albums.isEmpty()) return false
        val root = activity.findViewById<ViewGroup>(android.R.id.content)
        val insets = ViewCompat.getRootWindowInsets(root)?.getInsets(WindowInsetsCompat.Type.systemBars())
        val metrics = activity.resources.displayMetrics
        val width = min(dp(320), (metrics.widthPixels * 0.86f).toInt())

        val scrim = View(activity).apply {
            setBackgroundColor(0x59000000)
            alpha = 0f
            setOnClickListener { dismiss() }
            contentDescription = activity.getString(R.string.action_close)
        }
        val sheet = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            tag = "album_sidebar"
            isClickable = true
            background = GradientDrawable().apply {
                setColor(Ui.menuSurface(activity))
                setStroke(1, Ui.menuBorder(activity))
                val radius = dp(SIDE_RADIUS_DP).toFloat()
                cornerRadii = floatArrayOf(0f, 0f, radius, radius, radius, radius, 0f, 0f)
            }
            elevation = dp(12).toFloat()
            setPadding(dp(6), (insets?.top ?: 0) + dp(8), dp(6), (insets?.bottom ?: 0) + dp(6))
            translationX = -width.toFloat()
        }
        sheet.addView(TextView(activity).apply {
            text = activity.getString(R.string.msg_albums)
            textSize = 13f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setTextColor(Ui.menuText(activity))
            alpha = 0.78f
            setPadding(dp(10), dp(10), dp(10), dp(8))
        })
        val list = RecyclerView(activity).apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = RowsAdapter(albums)
            isVerticalScrollBarEnabled = true
            overScrollMode = View.OVER_SCROLL_NEVER
            tag = "album_sidebar_list"
        }
        sheet.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val current = albums.indexOfFirst { it.key == currentKey }
        if (current > 2) (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(current, dp(120))

        val frame = SwipeToCloseFrame(activity) { dismiss() }.apply {
            addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(sheet, FrameLayout.LayoutParams(width, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START))
        }
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        overlay = frame
        panel = sheet
        sheetWidth = width
        progress = 0f
        backCallback.isEnabled = true
        return true
    }

    fun dismiss(then: () -> Unit = {}) {
        val frame = overlay ?: return then()
        val width = sheetWidth.takeIf { it > 0 } ?: dp(320)
        backCallback.isEnabled = false
        overlay = null
        animate(progress, 0f, width) {
            (frame.parent as? ViewGroup)?.removeView(frame)
            panel = null
            then()
        }
        frame.isEnabled = false
    }

    private fun animate(from: Float, to: Float, width: Int, end: () -> Unit) {
        val frame = overlay ?: panel?.parent as? FrameLayout
        val sheet = panel ?: return end()
        val scrim = (sheet.parent as? ViewGroup)?.getChildAt(0)
        ValueAnimator.ofFloat(from, to).apply {
            duration = ANIMATION_MS
            addUpdateListener {
                val value = it.animatedValue as Float
                progress = value
                sheet.translationX = -width * (1f - value)
                scrim?.alpha = value
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) = end()
            })
            start()
        }
        frame?.invalidate()
    }

    private inner class RowsAdapter(private val albums: List<AlbumItem>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = albums.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(FrameLayout(activity).apply {
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(63))
                setPadding(0, 0, 0, dp(3))
            }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val album = albums[position]
            val container = holder.itemView as FrameLayout
            container.removeAllViews()
            val selected = album.key == currentKey
            val row = Ui.albumTargetRow(activity, album).apply {
                isSelected = selected
                background = RippleDrawable(
                    ColorStateList.valueOf((Ui.menuText(activity) and 0x00FFFFFF) or 0x22000000),
                    Ui.rounded(if (selected) Ui.menuHighlightSurface(activity) else Ui.menuOptionSurface(activity), 10, activity,
                        if (selected) Ui.accent(activity) else Ui.menuOptionSurface(activity), if (selected) 2 else 0),
                    Ui.rounded(Color.WHITE, 10, activity)
                )
                addView(TextView(activity).apply {
                    text = album.count.toString()
                    textSize = 13f
                    setTextColor(Ui.menuText(activity))
                    alpha = 0.66f
                    setPadding(dp(8), 0, 0, 0)
                })
                setOnClickListener {
                    if (selected) dismiss() else dismiss { onPick(album) }
                }
            }
            container.addView(row, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60)))
        }
    }

    /** Lets a swipe to the left anywhere over the sidebar close it. */
    private class SwipeToCloseFrame(context: android.content.Context, private val onClose: () -> Unit) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (dx < -slop * 3 && abs(dx) > abs(dy) * 1.6f) {
                        onClose()
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = isEnabled
    }

    private fun dp(value: Int): Int = Ui.dp(activity, value)

    private companion object {
        const val ANIMATION_MS = 180L
        const val SIDE_RADIUS_DP = 18
        const val FLING_DP_PER_SECOND = 600
    }
}

/** Opens the album sidebar from the album screen and switches albums from it. */
private fun AlbumMediaActivity.sidebar(): AlbumSidebar =
    albumSidebar ?: AlbumSidebar(this, albumKey) { album -> openAlbumFromSidebar(album) }.also { albumSidebar = it }

internal fun AlbumMediaActivity.openAlbumSidebar() {
    val sidebar = sidebar()
    if (sidebar.isShowing) return
    val shared = AlbumSidebarSource.albums
    if (shared.isNotEmpty()) {
        sidebar.show(shared)
        return
    }
    // The main screen is gone (the system closed the app): use the albums the "Mover" panel uses.
    val exposedKeys = intent.getStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS)?.toList()
    val hiddenKeys = prefs.getStringSet(GalleryPreferences.HIDDEN_FOLDER_KEYS, emptySet()).orEmpty()
    selectionCoordinator.loadMoveTargets(exposedKeys, hiddenKeys, null,
        exposedKeys != null && shouldIncludeHiddenFilesystem()) { targets ->
        if (!isFinishing) sidebar.show(targets)
    }
}

internal fun AlbumMediaActivity.openAlbumFromSidebar(album: AlbumItem) {
    if (album.key == albumKey) return
    val next = Intent(this, AlbumMediaActivity::class.java).apply {
        putExtra("album_key", album.key)
        putExtra("album_name", album.name)
        putExtra("include_hidden_filesystem", intent.getBooleanExtra("include_hidden_filesystem", false))
        intent.getStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS)?.let {
            putStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS, it)
        }
    }
    SecurityGate.requireFolder(this, album.key, album.name) {
        startActivity(next)
        finish()
        Ui.applyCloseTransition(this)
    }
}

/**
 * Watches the album grid for a swipe to the right. Like the Samsung gallery, it works over the
 * thumbnails (away from the system edge), and the panel follows the finger until it is released.
 */
internal fun AlbumMediaActivity.installAlbumSidebarGesture() {
    // The left edge is the system back gesture. A band in the middle of the grid is kept for the app
    // (Android allows up to 200dp per edge), so a swipe that starts at the edge there opens the sidebar.
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        grid.addOnLayoutChangeListener { view, _, top, _, bottom, _, _, _, _ ->
            val band = Ui.dp(this, EDGE_BAND_HEIGHT_DP)
            val middle = (bottom - top) / 2
            view.systemGestureExclusionRects = listOf(android.graphics.Rect(0, (middle - band / 2).coerceAtLeast(0),
                Ui.dp(this, EDGE_BAND_WIDTH_DP), (middle + band / 2).coerceAtMost(bottom - top)))
        }
    }
    val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
    var tracking = false
    var dragging = false
    var startX = 0f
    var startY = 0f
    var dragOriginX = 0f
    var velocity: VelocityTracker? = null
    fun finish(event: MotionEvent?) {
        val tracker = velocity
        val speed = if (tracker != null && event != null) {
            tracker.addMovement(event)
            tracker.computeCurrentVelocity(1000)
            tracker.xVelocity
        } else 0f
        tracker?.recycle()
        velocity = null
        tracking = false
        if (dragging) {
            dragging = false
            albumSidebar?.endDrag(speed)
        }
    }
    grid.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    tracking = !adapter.isSelectionMode() && layoutManager.orientation == RecyclerView.VERTICAL &&
                        albumSidebar?.isShowing != true
                    velocity?.recycle()
                    velocity = if (tracking) VelocityTracker.obtain().also { it.addMovement(event) } else null
                }
                MotionEvent.ACTION_POINTER_DOWN -> finish(null)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> finish(null)
                MotionEvent.ACTION_MOVE -> if (tracking) {
                    velocity?.addMovement(event)
                    val dx = event.x - startX
                    val dy = event.y - startY
                    if (adapter.isSelectionMode() || AlbumSidebarRules.isScroll(dx, dy, slop)) {
                        finish(null)
                    } else if (AlbumSidebarRules.startsDrag(dx, dy, slop)) {
                        if (sidebar().beginDrag(AlbumSidebarSource.albums)) {
                            // From here the grid stops scrolling and the panel follows the finger. The pull to
                            // refresh around the grid must not take the gesture over when the finger drifts down.
                            dragging = true
                            dragOriginX = event.x
                            recyclerView.parent?.requestDisallowInterceptTouchEvent(true)
                        } else {
                            // The list still has to load (the main screen was closed): open it when ready.
                            finish(null)
                            openAlbumSidebar()
                        }
                        return true
                    }
                }
            }
            return false
        }

        override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
            if (!dragging) return
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    velocity?.addMovement(event)
                    albumSidebar?.dragTo(event.x - dragOriginX + slop * 1.5f)
                }
                MotionEvent.ACTION_UP -> {
                    // Decide with where the finger left the screen: moves can arrive late in one batch.
                    albumSidebar?.dragTo(event.x - dragOriginX + slop * 1.5f)
                    finish(event)
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> finish(null)
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) = Unit
    })
}

private const val EDGE_BAND_HEIGHT_DP = 200
private const val EDGE_BAND_WIDTH_DP = 40
