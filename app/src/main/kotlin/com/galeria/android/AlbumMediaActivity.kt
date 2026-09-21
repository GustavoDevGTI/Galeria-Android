package com.galeria.android

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.lifecycleScope
import androidx.paging.LoadState
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.launch
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AlbumMediaActivity : ComponentActivity() {
    private lateinit var adapter: MediaRecyclerAdapter
    private lateinit var grid: AccessibleRecyclerView
    private lateinit var fastScroller: AlbumFastScroller
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var searchInput: EditText
    private lateinit var searchBox: LinearLayout
    private lateinit var selectAllChip: TextView
    private lateinit var moreButton: ImageButton
    private lateinit var emptyView: TextView
    private var albumKey: String? = null
    private var albumName: String = ""
    private lateinit var prefs: SharedPreferences
    private lateinit var cinemaPreferences: CinemaModePreferences
    private var gridSpacingDp = 3
    private var gridColumnCount = 0
    private var horizontalPinchScale = 1f
    private var lastHorizontalPinchSpan = 0f
    private var pinchGestureActive = false
    private var pinchGestureConsumed = false
    private var gridTouchDownX = 0f
    private var gridTouchDownY = 0f
    private var gridTouchClickCandidate = false
    private val gridTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private var gridDensityAnimationGeneration = 0
    private var dragging = false
    private var dragPosition = -1
    private var savedFirstVisible = 0
    private var draggedView: View? = null
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionActions: LinearLayout
    private lateinit var selectionActionDock: LinearLayout
    private lateinit var selectAllText: TextView
    private var spacingDecoration: RecyclerView.ItemDecoration? = null
    private var dragMoved = false
    private var showImages = true
    private var showVideos = true
    private var showGifs = true
    private var showRaw = true
    private var showSvgs = true
    private var listMode = false
    private var groupMode = GROUP_NONE
    private var mediaSortMode = MediaSortRules.SORT_CUSTOM
    private var mediaSortDescending = true
    private lateinit var catalogController: AlbumMediaCatalogController
    private lateinit var selectionCoordinator: AlbumSelectionActions
    private var pendingPagedScrollPosition = -1
    private var gridScrollState = RecyclerView.SCROLL_STATE_IDLE
    private var pendingPagingData: PagingData<MediaItem>? = null
    private var pendingMediaRefresh = false
    private var mediaRefreshScheduled = false
    private var gridPoolWarmupRemaining = 0
    private var warmedGridPoolViewType = -1
    private var firstResume = true
    private val completedRemovalUris = hashSetOf<String>()
    private val createdAtElapsedRealtime = SystemClock.elapsedRealtime()
    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchReload = Runnable {
        if (!isFinishing) loadMedia(false)
    }
    private val mediaRefreshHandler = Handler(Looper.getMainLooper())
    private val mediaRefreshRunnable = Runnable {
        mediaRefreshScheduled = false
        if (!isFinishing) {
            if (::grid.isInitialized && grid.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
                pendingMediaRefresh = true
            } else {
                pendingMediaRefresh = false
                refreshCatalogWithWorker()
            }
        }
    }
    private val gridPoolWarmup = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed || gridPoolWarmupRemaining <= 0 || !::grid.isInitialized) return
            if (grid.scrollState != RecyclerView.SCROLL_STATE_IDLE || dragging) {
                grid.postDelayed(this, GRID_POOL_WARMUP_RETRY_MS)
                return
            }
            val viewType = if (listMode) 1 else 0
            grid.recycledViewPool.putRecycledView(adapter.createViewHolder(grid, viewType))
            gridPoolWarmupRemaining--
            if (gridPoolWarmupRemaining > 0) grid.postDelayed(this, GRID_POOL_WARMUP_STEP_MS)
        }
    }
    private val finishFastScrollPreview = Runnable {
        if (::adapter.isInitialized) adapter.setFastScrollPreview(false)
    }
    private val mediaObserver = object : ContentObserver(mediaRefreshHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleMediaRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        completedRemovalUris.addAll(savedInstanceState?.getStringArrayList("completed_removal_uris").orEmpty())
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE)
        cinemaPreferences = CinemaModePreferences(prefs)
        Ui.applySystemBars(this)
        catalogController = AlbumMediaCatalogController(applicationContext)
        selectionCoordinator = AlbumSelectionActions(this, prefs)
        albumKey = intent.getStringExtra("album_key")
        albumName = intent.getStringExtra("album_name")?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.album_default_name)
        readAlbumOptions()
        buildLayout()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleToolbarBack()
        })
        registerMediaObserver()
        mediaRefreshHandler.postDelayed({
            if (!isFinishing) loadMedia(false)
        }, INITIAL_MEDIA_DELAY_MS)
    }

    override fun onResume() {
        super.onResume()
        Ui.applySystemBars(this)
        if (firstResume) {
            firstResume = false
            return
        }
        if (::adapter.isInitialized) {
            loadMedia(true)
        }
    }

    override fun onDestroy() {
        searchHandler.removeCallbacks(searchReload)
        if (::grid.isInitialized) grid.removeCallbacks(gridPoolWarmup)
        if (::grid.isInitialized) grid.removeCallbacks(finishFastScrollPreview)
        super.onDestroy()
        mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
        try {
            contentResolver.unregisterContentObserver(mediaObserver)
        } catch (_: Exception) {
        }
        catalogController.close()
        selectionCoordinator.close()
    }

    private fun refreshCatalogWithWorker() {
        catalogController.refreshCatalog(this, shouldIncludeHiddenFilesystem()) { succeeded ->
            if (isFinishing || isDestroyed) return@refreshCatalog
            if (succeeded) {
                loadMedia(true)
            } else if (::swipeRefresh.isInitialized) {
                swipeRefresh.isRefreshing = false
            }
        }
    }

    private fun registerMediaObserver() {
        try {
            contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, mediaObserver)
        } catch (_: Exception) {
        }
    }

    private fun scheduleMediaRefresh() {
        mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
        mediaRefreshScheduled = true
        val age = SystemClock.elapsedRealtime() - createdAtElapsedRealtime
        val delay = max(MEDIA_REFRESH_DEBOUNCE_MS, MEDIA_OBSERVER_GRACE_MS - age)
        mediaRefreshHandler.postDelayed(mediaRefreshRunnable, delay)
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(this@AlbumMediaActivity))
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            tag = TAG_ALBUM_TOOLBAR
            setPadding(Ui.dp(this@AlbumMediaActivity, 8), statusBarHeight() + Ui.dp(this@AlbumMediaActivity, 6), Ui.dp(this@AlbumMediaActivity, 8), Ui.dp(this@AlbumMediaActivity, 6))
        }
        val back = ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Ui.text(this@AlbumMediaActivity))
            setPadding(Ui.dp(this@AlbumMediaActivity, 8), Ui.dp(this@AlbumMediaActivity, 8), Ui.dp(this@AlbumMediaActivity, 8), Ui.dp(this@AlbumMediaActivity, 8))
            contentDescription = getString(R.string.album_back)
            setOnClickListener { handleToolbarBack() }
        }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)))

        gridSpacingDp = max(0, min(MAX_GRID_SPACING_DP, prefs.getInt(spacingKey(), 3)))
        searchBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            tag = TAG_ALBUM_SEARCH_TITLE
            contentDescription = getString(R.string.album_search_area_description)
            setPadding(Ui.dp(this@AlbumMediaActivity, 6), 0, Ui.dp(this@AlbumMediaActivity, 4), 0)
        }
        selectAllChip = Ui.title(this, "", 16).apply {
            gravity = Gravity.CENTER
            setTextColor(Ui.text(this@AlbumMediaActivity))
            visibility = View.GONE
            setOnClickListener { toggleSelectAll() }
            Ui.styleSelectionToggle(this, false)
        }
        searchBox.addView(selectAllChip, LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)).apply {
            marginStart = Ui.dp(this@AlbumMediaActivity, 3)
            marginEnd = Ui.dp(this@AlbumMediaActivity, 3)
        })

        searchInput = EditText(this).apply {
            hint = albumName
            setHintTextColor(Ui.text(this@AlbumMediaActivity))
            setTextColor(Ui.text(this@AlbumMediaActivity))
            textSize = 20f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            isCursorVisible = false
            contentDescription = getString(R.string.album_search_folder)
            compoundDrawablePadding = Ui.dp(this@AlbumMediaActivity, 8)
            setPadding(Ui.dp(this@AlbumMediaActivity, 6), 0, Ui.dp(this@AlbumMediaActivity, 6), 0)
            setOnFocusChangeListener { _, _ -> updateSearchPresentation() }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    if (::adapter.isInitialized && (adapter.isPagingMode() || groupMode == GROUP_NONE)) {
                        searchHandler.removeCallbacks(searchReload)
                        searchHandler.postDelayed(searchReload, 180L)
                    } else if (::adapter.isInitialized) {
                        adapter.applyFilter(s?.toString().orEmpty())
                        updateEmptyState()
                    }
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        searchBox.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        val searchParams = LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f).apply {
            marginStart = Ui.dp(this@AlbumMediaActivity, 2)
            marginEnd = Ui.dp(this@AlbumMediaActivity, 2)
        }
        bar.addView(searchBox, searchParams)

        moreButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_more_vertical)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Ui.text(this@AlbumMediaActivity))
            setPadding(Ui.dp(this@AlbumMediaActivity, 9), Ui.dp(this@AlbumMediaActivity, 9), Ui.dp(this@AlbumMediaActivity, 9), Ui.dp(this@AlbumMediaActivity, 9))
            contentDescription = getString(R.string.action_more_options)
            setOnClickListener {
                if (adapter.isSelectionMode()) {
                    exitSelectionMode()
                } else {
                    showFolderMenu(it)
                }
            }
        }
        bar.addView(moreButton, LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)))
        root.addView(bar)
        updateSearchPresentation()

        selectionBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.surface(this@AlbumMediaActivity), 8, this@AlbumMediaActivity)
            Ui.setPadding(this, 14, 8, 14, 8)
        }
        selectAllText = Ui.title(this, getString(R.string.action_select_all), 15).apply {
            setOnClickListener { toggleSelectAll() }
        }
        selectionBar.addView(selectAllText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val cancelSelection = Ui.title(this, getString(R.string.action_cancel), 15).apply {
            gravity = Gravity.END
            setOnClickListener { exitSelectionMode() }
        }
        selectionBar.addView(cancelSelection, LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 38)))
        selectionBar.visibility = View.GONE

        val content = FrameLayout(this)
        grid = AccessibleRecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@AlbumMediaActivity, mediaSpanCount()).also { this@AlbumMediaActivity.layoutManager = it }
            clipToPadding = false
            setHasFixedSize(true)
            setItemViewCacheSize(12)
            recycledViewPool.setMaxRecycledViews(0, 24)
            recycledViewPool.setMaxRecycledViews(1, 12)
            setBackgroundColor(Ui.bg(this@AlbumMediaActivity))
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    gridScrollState = newState
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        submitPendingPagingData()
                        if (pendingMediaRefresh) {
                            pendingMediaRefresh = false
                            scheduleMediaRefresh()
                        }
                    }
                }
            })
        }
        adapter = MediaRecyclerAdapter(this, object : MediaRecyclerAdapter.Callbacks {
            override fun onMediaClick(position: Int) {
                if (position !in 0 until adapter.getCount()) return
                if (!dragging) {
                    if (adapter.isSelectionMode()) {
                        adapter.toggleSelection(position)
                        updateSelectionUi()
                    } else {
                        openDetail(adapter.getItem(position), position)
                    }
                }
            }

            override fun onMediaLongClick(view: View, position: Int): Boolean {
                if (position !in 0 until adapter.getCount()) return true
                if (adapter.isPagingMode()) {
                    if (!adapter.isSelectionMode()) enterSelectionMode()
                    if (!adapter.isSelected(position)) adapter.selectPosition(position)
                    updateSelectionUi()
                    view.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.78f).setDuration(90).start()
                    return true
                }
                if (!adapter.isSelectionMode()) {
                    enterSelectionMode()
                    adapter.selectPosition(position)
                    updateSelectionUi()
                    view.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.78f).setDuration(90).start()
                    return true
                }
                if (!adapter.isSelected(position)) {
                    adapter.selectPosition(position)
                    updateSelectionUi()
                    view.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.78f).setDuration(90).start()
                    return true
                }
                dragging = true
                dragPosition = position
                dragMoved = false
                draggedView = view
                view.alpha = 0.55f
                view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                return true
            }
        })
        updateThumbnailRequestSize()
        adapter.addLoadStateListener { states ->
            if (!adapter.isPagingMode() || !::swipeRefresh.isInitialized) return@addLoadStateListener
            val refresh = states.refresh
            swipeRefresh.isRefreshing = refresh is LoadState.Loading && adapter.getCount() > 0
            if (refresh is LoadState.NotLoading) {
                updateEmptyState()
                updateSelectionUi()
                warmGridPoolGradually()
                if (pendingPagedScrollPosition > 0 && adapter.getCount() > 0) {
                    grid.scrollToPosition(min(pendingPagedScrollPosition, adapter.getCount() - 1))
                    pendingPagedScrollPosition = -1
                }
            } else if (refresh is LoadState.Error) {
                emptyView.setText(R.string.album_load_failed)
                emptyView.visibility = if (adapter.getCount() == 0) View.VISIBLE else View.GONE
            }
        }
        applyViewMode()
        grid.adapter = adapter
        grid.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gridTouchDownX = event.x
                    gridTouchDownY = event.y
                    gridTouchClickCandidate = !dragging
                }
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_CANCEL -> gridTouchClickCandidate = false
                MotionEvent.ACTION_MOVE -> {
                    if (dragging || abs(event.x - gridTouchDownX) > gridTouchSlop ||
                        abs(event.y - gridTouchDownY) > gridTouchSlop
                    ) {
                        gridTouchClickCandidate = false
                    }
                }
            }
            if (event.action == MotionEvent.ACTION_DOWN && mediaRefreshScheduled) {
                mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
                mediaRefreshScheduled = false
                pendingMediaRefresh = true
            }
            val pinchCandidate = !listMode && !dragging && event.pointerCount > 1
            if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && pinchCandidate) {
                horizontalPinchScale = 1f
                lastHorizontalPinchSpan = horizontalPointerSpan(event)
                pinchGestureActive = true
                pinchGestureConsumed = true
                grid.stopScroll()
                grid.parent?.requestDisallowInterceptTouchEvent(true)
                if (::swipeRefresh.isInitialized) swipeRefresh.isEnabled = false
                return@setOnTouchListener true
            }
            if (event.actionMasked == MotionEvent.ACTION_MOVE && pinchGestureActive) {
                val currentSpan = horizontalPointerSpan(event)
                if (lastHorizontalPinchSpan > 0f && currentSpan > 0f) {
                    val factor = currentSpan / lastHorizontalPinchSpan
                    if (factor.isFinite() && factor in 0.5f..2f) {
                        horizontalPinchScale *= factor
                        val delta = GridColumnRules.columnDelta(horizontalPinchScale)
                        if (delta != 0) {
                            horizontalPinchScale = 1f
                            changeGridColumnCount(
                                delta,
                                horizontalPointerCenterX(event),
                                horizontalPointerCenterY(event)
                            )
                        }
                    }
                }
                lastHorizontalPinchSpan = currentSpan
                return@setOnTouchListener true
            }
            if (event.actionMasked == MotionEvent.ACTION_POINTER_UP && pinchGestureActive) {
                pinchGestureActive = false
                horizontalPinchScale = 1f
                lastHorizontalPinchSpan = 0f
                if (::swipeRefresh.isInitialized) swipeRefresh.isEnabled = true
                return@setOnTouchListener true
            }
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                val consumed = pinchGestureConsumed
                pinchGestureActive = false
                pinchGestureConsumed = false
                horizontalPinchScale = 1f
                lastHorizontalPinchSpan = 0f
                if (::swipeRefresh.isInitialized) swipeRefresh.isEnabled = true
                if (consumed) return@setOnTouchListener true
            }
            if (pinchCandidate || pinchGestureActive || pinchGestureConsumed) {
                return@setOnTouchListener true
            }
            if (!dragging) {
                if (event.actionMasked == MotionEvent.ACTION_UP && gridTouchClickCandidate) grid.performClick()
                if (event.actionMasked == MotionEvent.ACTION_UP) gridTouchClickCandidate = false
                return@setOnTouchListener false
            }
            if (event.action == MotionEvent.ACTION_MOVE) {
                val targetView = grid.findChildViewUnder(event.x, event.y)
                val target = if (targetView == null) RecyclerView.NO_POSITION else grid.getChildAdapterPosition(targetView)
                if (adapter.isSelectionMode() && target >= 0 && target != dragPosition && adapter.moveSelectedBlock(target)) {
                    dragPosition = target
                    dragMoved = true
                    animateGridMove()
                } else if (!adapter.isSelectionMode() && target >= 0 && target != dragPosition && adapter.moveVisible(dragPosition, target)) {
                    dragPosition = target
                    dragMoved = true
                    animateGridMove()
                }
                return@setOnTouchListener true
            }
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                finishDrag()
                if (dragMoved) {
                    saveCustomOrder()
                    if (adapter.isSelectionMode()) {
                        exitSelectionMode()
                    }
                }
                return@setOnTouchListener true
            }
            true
        }
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Ui.accent(this@AlbumMediaActivity))
            setProgressBackgroundColorSchemeColor(Ui.surface(this@AlbumMediaActivity))
            setOnRefreshListener {
                refreshCatalogWithWorker()
            }
        }
        swipeRefresh.addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(swipeRefresh, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fastScroller = AlbumFastScroller(this, grid) { active ->
            grid.removeCallbacks(finishFastScrollPreview)
            if (active) {
                adapter.setFastScrollPreview(true)
            } else {
                grid.postDelayed(finishFastScrollPreview, FAST_SCROLL_PREVIEW_DURATION_MS)
            }
        }
        val fastScrollParams = FrameLayout.LayoutParams(
            Ui.dp(this, 28),
            ViewGroup.LayoutParams.MATCH_PARENT,
            Gravity.END
        ).apply {
            topMargin = Ui.dp(this@AlbumMediaActivity, 6)
            marginEnd = Ui.dp(this@AlbumMediaActivity, 2)
            bottomMargin = Ui.dp(this@AlbumMediaActivity, 6)
        }
        content.addView(fastScroller, fastScrollParams)

        emptyView = Ui.label(this, getString(R.string.album_empty)).apply {
            visibility = View.GONE
        }
        content.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        selectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.surface(this@AlbumMediaActivity))
            setPadding(0, Ui.dp(this@AlbumMediaActivity, 1), 0, navigationBarHeight())
        }
        selectionActionDock = Ui.selectionActionDock(this)
        selectionActions.addView(selectionActionDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSelectionAction(R.drawable.ic_share, getString(R.string.action_share)) { shareSelected() }
        addSelectionAction(R.drawable.ic_heart, getString(R.string.action_favorite)) { favoriteSelected() }
        addSelectionAction(R.drawable.ic_trash, getString(R.string.action_delete)) { confirmDeleteSelected() }
        addSelectionAction(R.drawable.ic_arrow_right, getString(R.string.action_move)) { askMoveSelected() }
        selectionActions.visibility = View.GONE
        root.addView(selectionActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun warmGridPoolGradually() {
        if (!::grid.isInitialized || adapter.getCount() == 0) return
        val viewType = if (listMode) 1 else 0
        if (warmedGridPoolViewType == viewType) return
        warmedGridPoolViewType = viewType
        val target = if (listMode) 4 else gridColumnCount * 2
        gridPoolWarmupRemaining = max(gridPoolWarmupRemaining, target)
        grid.removeCallbacks(gridPoolWarmup)
        grid.postDelayed(gridPoolWarmup, GRID_POOL_WARMUP_STEP_MS)
    }

    private fun addSelectionAction(icon: Int, label: String, listener: () -> Unit) {
        Ui.addSelectionActionToDock(selectionActionDock, Ui.selectionAction(this, icon, label, listener))
    }

    private fun showFolderMenu(anchor: View) {
        val filter = getString(R.string.action_filter_media)
        val group = getString(R.string.album_group_by)
        val sort = getString(R.string.action_sort_by)
        val viewMode = getString(R.string.album_view_mode)
        val createFolder = getString(R.string.action_create_folder)
        val random = getString(R.string.album_random)
        val spacing = getString(R.string.album_grid_spacing)
        val cinemaMode = getString(R.string.album_cinema_mode)
        val options = buildList {
            addAll(listOf(filter, group, sort, viewMode, createFolder, random, spacing))
            if (CinemaModeRules.supportsAlbum(albumKey)) add(cinemaMode)
        }
        Ui.showPopupOptions(
            anchor,
            options,
            selectedItems = if (cinemaPreferences.isEnabled(albumKey)) setOf(cinemaMode) else emptySet()
        ) { selected ->
            when (selected) {
                filter -> showMediaFilterDialog()
                group -> showGroupDialog()
                sort -> showMediaSortDialog()
                viewMode -> showViewModeDialog()
                createFolder -> showCreateFolderDialog()
                random -> startRandomPlayback()
                spacing -> showSpacingDialog()
                cinemaMode -> toggleAlbumCinemaMode()
            }
        }
    }

    private fun toggleAlbumCinemaMode() {
        val enabled = cinemaPreferences.toggle(albumKey)
        Ui.toast(
            this,
            getString(if (enabled) R.string.album_cinema_mode_enabled else R.string.album_cinema_mode_disabled)
        )
    }

    private fun readAlbumOptions() {
        showImages = prefs.getBoolean(optionKey("filter_images"), true)
        showVideos = prefs.getBoolean(optionKey("filter_videos"), true)
        showGifs = prefs.getBoolean(optionKey("filter_gifs"), true)
        showRaw = prefs.getBoolean(optionKey("filter_raw"), true)
        showSvgs = prefs.getBoolean(optionKey("filter_svg"), true)
        listMode = prefs.getBoolean(optionKey("list_mode"), false)
        gridColumnCount = GridColumnRules.normalized(
            prefs.getInt(PREF_GRID_COLUMNS, 0),
            mediaSpanCount()
        )
        groupMode = prefs.getString(optionKey("group_mode"), GROUP_NONE) ?: GROUP_NONE
        mediaSortMode = prefs.getString(optionKey("sort_mode"), MediaSortRules.SORT_CUSTOM)
            ?: MediaSortRules.SORT_CUSTOM
        mediaSortDescending = prefs.getBoolean(optionKey("sort_desc"), true)
    }

    private fun showMediaFilterDialog() {
        val labels = resources.getStringArray(R.array.album_media_filter_labels)
        val checked = booleanArrayOf(showImages, showVideos, showGifs, showRaw, showSvgs)
        Ui.showMultiChoiceDialog(this, getString(R.string.action_filter_media), labels, checked) { selected ->
            showImages = selected[0]
            showVideos = selected[1]
            showGifs = selected[2]
            showRaw = selected[3]
            showSvgs = selected[4]
            prefs.edit()
                .putBoolean(optionKey("filter_images"), showImages)
                .putBoolean(optionKey("filter_videos"), showVideos)
                .putBoolean(optionKey("filter_gifs"), showGifs)
                .putBoolean(optionKey("filter_raw"), showRaw)
                .putBoolean(optionKey("filter_svg"), showSvgs)
                .apply()
            loadMedia(true)
        }
    }

    private fun showGroupDialog() {
        val labels = resources.getStringArray(R.array.album_group_labels)
        val values = arrayOf(GROUP_NONE, GROUP_TYPE, GROUP_EXTENSION, GROUP_DAY, GROUP_MONTH)
        var choice = values.indexOf(groupMode).takeIf { it >= 0 } ?: 0
        Ui.showChoiceDialog(this, getString(R.string.album_group_by), labels, choice) { which ->
            choice = which
            groupMode = values[choice]
            prefs.edit().putString(optionKey("group_mode"), groupMode).apply()
            loadMedia(true)
        }
    }

    private fun showViewModeDialog() {
        val labels = resources.getStringArray(R.array.album_view_mode_labels)
        var choice = if (listMode) 1 else 0
        Ui.showChoiceDialog(this, getString(R.string.album_view_mode), labels, choice) { which ->
            choice = which
            listMode = choice == 1
            prefs.edit().putBoolean(optionKey("list_mode"), listMode).apply()
            applyViewMode()
        }
    }

    private fun showCreateFolderDialog() {
        Ui.showTextInputDialog(
            this,
            getString(R.string.action_create_folder),
            getString(R.string.field_title),
            message = folderDisplayPath()
        ) { name ->
            createFolder(name)
        }
    }

    private fun showSpacingDialog() {
        lateinit var dialog: AlertDialog
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.menuSurface(this@AlbumMediaActivity), 14, this@AlbumMediaActivity)
            clipToOutline = true
            setPadding(
                Ui.dp(this@AlbumMediaActivity, 24),
                Ui.dp(this@AlbumMediaActivity, 20),
                Ui.dp(this@AlbumMediaActivity, 24),
                Ui.dp(this@AlbumMediaActivity, 8)
            )
        }
        panel.addView(
            TextView(this).apply {
                setText(R.string.album_grid_spacing)
                textSize = 18f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(Ui.menuText(this@AlbumMediaActivity))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val hint = TextView(this).apply {
            setText(R.string.album_grid_spacing_hint)
            textSize = 13f
            setTextColor(Ui.menuText(this@AlbumMediaActivity))
            alpha = 0.78f
            gravity = Gravity.START
            setPadding(0, Ui.dp(this@AlbumMediaActivity, 6), 0, Ui.dp(this@AlbumMediaActivity, 12))
        }
        panel.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val seekBar = SeekBar(this).apply {
            max = MAX_GRID_SPACING_DP
            progress = MAX_GRID_SPACING_DP - gridSpacingDp
            progressTintList = android.content.res.ColorStateList.valueOf(Ui.accent(this@AlbumMediaActivity))
            thumbTintList = android.content.res.ColorStateList.valueOf(Ui.accent(this@AlbumMediaActivity))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.muted(this@AlbumMediaActivity))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                    gridSpacingDp = MAX_GRID_SPACING_DP - progress
                    applyGridSpacing()
                    prefs.edit().putInt(spacingKey(), gridSpacingDp).apply()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        panel.addView(seekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)))
        panel.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, Ui.dp(this@AlbumMediaActivity, 8), 0, 0)
                addView(
                    TextView(this@AlbumMediaActivity).apply {
                        setText(R.string.action_ok)
                        textSize = 14f
                        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                        setTextColor(Ui.menuText(this@AlbumMediaActivity))
                        gravity = Gravity.CENTER_VERTICAL or Gravity.START
                        minimumHeight = Ui.dp(this@AlbumMediaActivity, 50)
                        isClickable = true
                        isFocusable = true
                        background = Ui.rounded(
                            Ui.blend(Ui.menuSurface(this@AlbumMediaActivity), Ui.menuText(this@AlbumMediaActivity), 0.08f),
                            0,
                            this@AlbumMediaActivity
                        )
                        setPadding(
                            Ui.dp(this@AlbumMediaActivity, 14),
                            Ui.dp(this@AlbumMediaActivity, 12),
                            Ui.dp(this@AlbumMediaActivity, 14),
                            Ui.dp(this@AlbumMediaActivity, 12)
                        )
                        setOnClickListener { dialog.dismiss() }
                    },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        dialog = AlertDialog.Builder(this).setView(panel).create()
        Ui.showSidePanel(dialog)
    }

    private fun applyGridSpacing() {
        if (!::grid.isInitialized) return
        val gap = Ui.dp(this, gridSpacingDp)
        spacingDecoration?.let(grid::removeItemDecoration)
        spacingDecoration = GridSpacingItemDecoration(gap, layoutManager.spanCount).also(grid::addItemDecoration)
        grid.setPadding(0, 0, 0, Ui.dp(this, 16))
        grid.invalidateItemDecorations()
        updateThumbnailRequestSize()
    }

    private fun enterSelectionMode() {
        adapter.setSelectionMode(true)
    }

    private fun exitSelectionMode() {
        val wasSelecting = adapter.isSelectionMode()
        adapter.clearSelection()
        updateSelectionUi()
        if (wasSelecting && groupMode == GROUP_NONE) {
            loadMedia(true)
        }
    }

    private fun updateSelectionUi() {
        if (adapter.isSelectionMode() && adapter.selectedCount() == 0) {
            adapter.setSelectionMode(false)
        }
        val active = adapter.isSelectionMode() && adapter.selectedCount() > 0
        selectionBar.visibility = View.GONE
        selectionActions.visibility = if (active) View.VISIBLE else View.GONE
        selectAllChip.visibility = if (active) View.VISIBLE else View.GONE
        Ui.styleSelectionToggle(selectAllChip, adapter.allVisibleSelected())
        searchInput.isEnabled = !active
        moreButton.setImageResource(if (active) R.drawable.ic_back else R.drawable.ic_more_vertical)
        moreButton.contentDescription = getString(
            if (active) R.string.action_cancel_selection else R.string.action_more_options
        )
        if (active) {
            searchInput.clearFocus()
            hideKeyboard()
        }
        updateSearchPresentation()
    }

    private fun handleToolbarBack() {
        if (::adapter.isInitialized && adapter.isSelectionMode()) {
            exitSelectionMode()
            return
        }
        if (::searchInput.isInitialized && (searchInput.hasFocus() || searchInput.text.isNotEmpty())) {
            searchInput.setText("")
            searchInput.clearFocus()
            hideKeyboard()
            updateSearchPresentation()
            return
        }
        finish()
    }

    private fun updateSearchPresentation() {
        if (!::searchInput.isInitialized || !::searchBox.isInitialized) return
        val selecting = ::adapter.isInitialized && adapter.isSelectionMode() && adapter.selectedCount() > 0
        val searching = !selecting && (searchInput.hasFocus() || searchInput.text.isNotEmpty())

        searchBox.background = when {
            selecting -> Ui.rounded(Ui.surface(this), 18, this)
            searching -> Ui.rounded(Ui.search(this), 18, this)
            else -> Ui.rounded(Ui.search(this), 18, this)
        }
        searchInput.hint = when {
            selecting -> resources.getQuantityString(
                R.plurals.selected_count,
                adapter.selectedCount(),
                adapter.selectedCount()
            )
            searching -> getString(R.string.album_search_folder)
            else -> getString(R.string.album_search_in, albumName)
        }
        searchInput.setHintTextColor(if (selecting) Ui.text(this) else Ui.muted(this))
        searchInput.textSize = if (selecting) 20f else if (searching) 14f else 15f
        searchInput.setTypeface(Typeface.DEFAULT, if (selecting) Typeface.BOLD else Typeface.NORMAL)
        searchInput.isCursorVisible = searching && searchInput.hasFocus()

        val searchIcon = if (selecting) null else getDrawable(R.drawable.ic_search)?.mutate()?.apply {
            setTint(Ui.muted(this@AlbumMediaActivity))
        }
        searchInput.setCompoundDrawablesRelativeWithIntrinsicBounds(searchIcon, null, null, null)
    }

    private fun hideKeyboard() {
        val inputManager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        inputManager.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun toggleSelectAll() {
        if (adapter.allVisibleSelected()) {
            exitSelectionMode()
        } else {
            adapter.selectAllVisible()
            updateSelectionUi()
        }
    }

    private fun shareSelected() {
        val selected = adapter.selectedItems()
        val share = selectionCoordinator.shareIntent(selected) ?: return
        startActivity(Intent.createChooser(share, getString(R.string.action_share)))
    }

    private fun favoriteSelected() {
        val selected = adapter.selectedItems()
        val added = selectionCoordinator.addToFavorites(selected)
        if (added == 0) return
        Ui.toast(this, resources.getQuantityString(R.plurals.items_added_to_favorites, added, added))
        exitSelectionMode()
    }

    private fun confirmDeleteSelected() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        Ui.showConfirmationDialog(
            this,
            getString(R.string.album_delete_selected_title),
            getString(
                R.string.album_delete_selected_message,
                resources.getQuantityString(R.plurals.files_count, selected.size, selected.size)
            ),
            getString(R.string.action_delete)
        ) { deleteSelected(selected) }
    }

    private fun deleteSelected(selected: List<MediaItem>) {
        if (AlbumMediaRules.requiresFileManagement(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                MediaActions.hasAllFilesAccess(this)
            )
        ) {
            requestFileManagementAccess()
            return
        }
        val result = selectionCoordinator.delete(selected, REQ_DELETE)
        completedRemovalUris.addAll(result.completedItems.map { MediaIdentityRules.canonicalKey(it.uri.toString()) })
        adapter.removeCompletedItems(result.completedItems.map { it.uri.toString() })
        updateEmptyState()
        Ui.toast(
            this,
            resources.getQuantityString(R.plurals.items_deleted, result.completed, result.completed)
        )
        exitSelectionMode()
    }

    private fun askMoveSelected() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        val exposedKeys = intent.getStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS)?.toSet()
        val hiddenKeys = prefs.getStringSet("hidden_folder_keys", emptySet()).orEmpty()
        selectionCoordinator.loadMoveTargets(
            exposedKeys,
            hiddenKeys,
            albumKey,
            exposedKeys != null && shouldIncludeHiddenFilesystem()
        ) { targets ->
            if (isFinishing || !moreButton.isAttachedToWindow) return@loadMoveTargets
            if (targets.isEmpty()) {
                Ui.toast(this, getString(R.string.main_no_move_target))
                return@loadMoveTargets
            }
            Ui.showAlbumTargets(moreButton, getString(R.string.action_move_to), targets) { album ->
                moveSelected(selected, album)
            }
        }
    }

    private fun showMediaSortDialog() {
        val labels = resources.getStringArray(R.array.album_sort_labels)
        val values = arrayOf(
            MediaSortRules.SORT_CUSTOM,
            MediaSortRules.SORT_DATE,
            MediaSortRules.SORT_NAME,
            MediaSortRules.SORT_SIZE,
            MediaSortRules.SORT_DURATION,
            MediaSortRules.SORT_TYPE
        )
        val selected = values.indexOf(mediaSortMode).takeIf { it >= 0 } ?: 0
        Ui.showChoiceDialog(
            this,
            getString(R.string.action_sort_by),
            labels,
            selected,
            message = getString(R.string.album_sort_group_hint),
            neutralText = if (mediaSortMode == MediaSortRules.SORT_CUSTOM) {
                null
            } else if (mediaSortDescending) {
                getString(R.string.action_descending)
            } else {
                getString(R.string.action_ascending)
            },
            onNeutral = if (mediaSortMode == MediaSortRules.SORT_CUSTOM) null else {
                {
                    mediaSortDescending = !mediaSortDescending
                    saveMediaSortOptions()
                    loadMedia(true)
                }
            }
        ) { which ->
            mediaSortMode = values[which]
            saveMediaSortOptions()
            loadMedia(true)
        }
    }

    private fun saveMediaSortOptions() {
        prefs.edit()
            .putString(optionKey("sort_mode"), mediaSortMode)
            .putBoolean(optionKey("sort_desc"), mediaSortDescending)
            .apply()
    }

    private fun moveSelected(selected: List<MediaItem>, destination: AlbumItem) {
        if (AlbumMediaRules.requiresFileManagement(
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                MediaActions.hasAllFilesAccess(this)
            )
        ) {
            requestFileManagementAccess()
            return
        }
        val result = selectionCoordinator.move(selected, destination.path.ifBlank { destination.name })
        if (albumKey != "all_media") {
            completedRemovalUris.addAll(result.completedItems.map { MediaIdentityRules.canonicalKey(it.uri.toString()) })
            adapter.removeCompletedItems(result.completedItems.map { it.uri.toString() })
            updateEmptyState()
        }
        Ui.toast(
            this,
            resources.getQuantityString(R.plurals.items_moved, result.completed, result.completed)
        )
        if (albumKey in result.emptiedAlbumKeys && result.completed > 0) {
            openMoveDestination(destination.key, destination.name)
        } else {
            exitSelectionMode()
            loadMedia(true)
        }
    }

    private fun openMoveDestination(key: String, name: String) {
        startActivity(MediaOperationNavigation.destinationIntent(this, intent, key, name))
        finish()
    }

    private fun loadMedia(preserveScroll: Boolean) {
        val targetPosition = AlbumMediaRules.scrollTarget(
            preserveScroll,
            layoutManager.findFirstVisibleItemPosition(),
            savedFirstVisible
        )
        val query = if (::searchInput.isInitialized) searchInput.text.toString() else ""
        val options = AlbumMediaCatalogOptions(
            albumKey,
            shouldIncludeHiddenFilesystem(),
            query,
            MediaFilterOptions(showImages, showVideos, showGifs, showRaw, showSvgs),
            groupMode,
            mediaSortMode,
            mediaSortDescending,
            adapter.isSelectionMode()
        )
        val usePaging = AlbumMediaRules.shouldUsePaging(albumKey, groupMode, adapter.isSelectionMode())
        if (usePaging) {
            if (adapter.getCount() == 0 && ::emptyView.isInitialized) {
                emptyView.setText(R.string.album_loading_media)
                emptyView.visibility = View.VISIBLE
            }
            pendingPagedScrollPosition = targetPosition
        } else if (::adapter.isInitialized && adapter.getCount() == 0 && ::emptyView.isInitialized) {
            emptyView.setText(R.string.main_loading_media)
            emptyView.visibility = View.VISIBLE
        }
        catalogController.load(
            lifecycleScope,
            options,
            onItems = { items ->
                if (!isFinishing) showMedia(items, query, targetPosition)
            },
            onPage = { page ->
                val filteredPage = page.filter { !wasRemoved(it) }
                if (gridScrollState == RecyclerView.SCROLL_STATE_IDLE && !dragging) {
                    adapter.submitPagingData(filteredPage)
                } else {
                    pendingPagingData = filteredPage
                }
            }
        )
    }

    private fun submitPendingPagingData() {
        val page = pendingPagingData ?: return
        if (dragging || gridScrollState != RecyclerView.SCROLL_STATE_IDLE || isFinishing) return
        pendingPagingData = null
        lifecycleScope.launch {
            adapter.submitPagingData(page)
        }
    }

    private fun showMedia(items: List<MediaItem>, query: String, targetPosition: Int) {
        // Drop the temporary exclusion once a fresh delivery confirms the item has left.
        // If it is later moved back into this folder it must become visible again.
        completedRemovalUris.retainAll(items.mapTo(HashSet()) { MediaIdentityRules.canonicalKey(it.uri.toString()) })
        adapter.submit(items.filterNot(::wasRemoved), query)
        updateEmptyState()
        updateSelectionUi()
        if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
        if (targetPosition > 0) {
            grid.scrollToPosition(min(targetPosition, max(0, adapter.getCount() - 1)))
        }
    }

    private fun saveCustomOrder() {
        val order = adapter.currentOrder()
        mediaSortMode = MediaSortRules.SORT_CUSTOM
        saveMediaSortOptions()
        catalogController.saveCustomOrder(albumKey, order) {
            if (!isFinishing) Ui.toast(this, getString(R.string.album_custom_order_saved))
        }
    }

    private fun wasRemoved(item: MediaItem): Boolean =
        MediaIdentityRules.canonicalKey(item.uri.toString()) in completedRemovalUris

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("completed_removal_uris", ArrayList(completedRemovalUris))
        super.onSaveInstanceState(outState)
    }

    private fun spacingKey(): String = "grid_spacing_global"

    private fun finishDrag() {
        dragging = false
        dragPosition = -1
        draggedView?.animate()?.alpha(1f)?.scaleX(1f)?.scaleY(1f)?.setDuration(110)?.start()
        draggedView = null
        adapter.refreshSelectionVisuals()
        submitPendingPagingData()
    }

    private fun animateGridMove() {
        grid.animate()
            .scaleX(0.996f)
            .scaleY(0.996f)
            .setInterpolator(DecelerateInterpolator())
            .setDuration(55)
            .withEndAction {
                grid.animate().scaleX(1f).scaleY(1f).setDuration(75).start()
            }
            .start()
    }

    private fun updateEmptyState() {
        emptyView.visibility = if (adapter.getCount() == 0) View.VISIBLE else View.GONE
    }

    private fun startRandomPlayback() {
        if (adapter.getCount() == 0) {
            Ui.toast(this, getString(R.string.album_no_media_to_play))
            return
        }
        val position = Random().nextInt(adapter.getCount())
        openDetail(adapter.getItem(position), position, true)
    }

    private fun applyViewMode() {
        if (!::grid.isInitialized || !::adapter.isInitialized) return
        gridDensityAnimationGeneration++
        grid.animate().cancel()
        grid.alpha = 1f
        grid.scaleX = 1f
        grid.scaleY = 1f
        layoutManager.spanCount = if (listMode) 1 else gridColumnCount
        adapter.setListMode(listMode)
        grid.contentDescription = if (listMode) {
            getString(R.string.album_media_list_description)
        } else {
            resources.getQuantityString(
                R.plurals.media_grid_columns_description,
                gridColumnCount,
                gridColumnCount
            )
        }
        applyGridSpacing()
    }

    private fun changeGridColumnCount(delta: Int, focusX: Float, focusY: Float) {
        if (listMode || !::layoutManager.isInitialized || !::grid.isInitialized) return
        val next = GridColumnRules.changed(gridColumnCount, delta)
        if (next == gridColumnCount) return
        val focusView = grid.findChildViewUnder(focusX, focusY)
        val focusPosition = focusView?.let(grid::getChildAdapterPosition) ?: RecyclerView.NO_POSITION
        val anchorPosition = if (focusPosition != RecyclerView.NO_POSITION) {
            focusPosition
        } else {
            layoutManager.findFirstVisibleItemPosition()
        }
        val anchorOffset = focusView?.top
            ?: layoutManager.findViewByPosition(anchorPosition)?.top
            ?: grid.paddingTop
        gridColumnCount = next
        prefs.edit().putInt(PREF_GRID_COLUMNS, gridColumnCount).apply()
        grid.contentDescription = resources.getQuantityString(
            R.plurals.media_grid_columns_description,
            gridColumnCount,
            gridColumnCount
        )
        animateGridDensityChange(delta, focusX, focusY, anchorPosition, anchorOffset)
    }

    private fun horizontalPointerSpan(event: MotionEvent): Float =
        if (event.pointerCount >= 2) abs(event.getX(0) - event.getX(1)) else 0f

    private fun horizontalPointerCenterX(event: MotionEvent): Float =
        if (event.pointerCount >= 2) (event.getX(0) + event.getX(1)) / 2f else grid.width / 2f

    private fun horizontalPointerCenterY(event: MotionEvent): Float =
        if (event.pointerCount >= 2) (event.getY(0) + event.getY(1)) / 2f else grid.height / 2f

    private fun animateGridDensityChange(
        delta: Int,
        focusX: Float,
        focusY: Float,
        anchorPosition: Int,
        anchorOffset: Int
    ) {
        val generation = ++gridDensityAnimationGeneration
        val exitScale = if (delta > 0) 0.982f else 1.018f
        val entryScale = if (delta > 0) 1.018f else 0.982f
        grid.animate().cancel()
        grid.pivotX = focusX.coerceIn(0f, grid.width.toFloat())
        grid.pivotY = focusY.coerceIn(0f, grid.height.toFloat())
        grid.animate()
            .alpha(0.72f)
            .scaleX(exitScale)
            .scaleY(exitScale)
            .setInterpolator(DecelerateInterpolator())
            .setDuration(GRID_DENSITY_EXIT_MS)
            .withEndAction {
                if (generation != gridDensityAnimationGeneration || isFinishing || isDestroyed) {
                    return@withEndAction
                }
                layoutManager.spanCount = gridColumnCount
                applyGridSpacing()
                updateThumbnailRequestSize()
                if (anchorPosition != RecyclerView.NO_POSITION) {
                    layoutManager.scrollToPositionWithOffset(anchorPosition, anchorOffset)
                }
                grid.scaleX = entryScale
                grid.scaleY = entryScale
                warmedGridPoolViewType = -1
                warmGridPoolGradually()
                grid.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setInterpolator(DecelerateInterpolator(1.5f))
                    .setDuration(GRID_DENSITY_ENTRY_MS)
                    .withEndAction {
                        if (generation == gridDensityAnimationGeneration) {
                            grid.alpha = 1f
                            grid.scaleX = 1f
                            grid.scaleY = 1f
                        }
                    }
                    .start()
            }
            .start()
    }

    private fun updateThumbnailRequestSize() {
        if (!::adapter.isInitialized || !::layoutManager.isInitialized) return
        val size = if (listMode) {
            Ui.dp(this, 82)
        } else {
            val spans = max(1, layoutManager.spanCount)
            val totalGaps = Ui.dp(this, gridSpacingDp) * max(0, spans - 1)
            max(Ui.dp(this, 96), (resources.displayMetrics.widthPixels - totalGaps) / spans)
        }
        adapter.setGridThumbnailSize(size)
    }

    private fun mediaSpanCount(): Int =
        max(2, resources.displayMetrics.widthPixels / Ui.dp(this, 126))

    private fun optionKey(suffix: String): String =
        "album_${suffix}_${albumKey?.hashCode() ?: "all"}"

    private fun folderDisplayPath(): String {
        val path = currentRelativeFolder()
        return if (path.isEmpty()) {
            getString(R.string.album_internal_storage)
        } else {
            getString(R.string.album_internal_storage_path, path)
        }
    }

    private fun currentRelativeFolder(): String {
        val key = albumKey
        if (key != null && key != "all_media" && key.endsWith("/")) {
            return key
        }
        if (::adapter.isInitialized && adapter.getCount() > 0) {
            return adapter.getItem(0).relativePath
        }
        return ""
    }

    private fun createFolder(rawName: String) {
        val cleanName = MediaActions.cleanFolderName(rawName)
        if (cleanName.isEmpty()) {
            Ui.toast(this, getString(R.string.album_enter_folder_name))
            return
        }
        val relative = currentRelativeFolder()
        val base = if (relative.isEmpty()) {
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        } else {
            File(Environment.getExternalStorageDirectory(), relative)
        }
        val target = File(base, cleanName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            requestFileManagementAccess()
            return
        }
        if (target.exists()) {
            Ui.toast(this, getString(R.string.album_folder_exists))
            return
        }
        if (MediaActions.createFolder(this, target)) {
            Ui.toast(this, getString(R.string.album_folder_created))
        } else {
            Ui.toast(this, getString(R.string.album_folder_create_failed))
        }
    }

    private fun requestFileManagementAccess() {
        Ui.showConfirmationDialog(
            this,
            getString(R.string.access_full_management_title),
            getString(R.string.access_batch_operation_explanation),
            getString(R.string.action_allow)
        ) { MediaActions.requestAllFilesAccess(this) }
    }

    private fun shouldIncludeHiddenFilesystem(): Boolean =
        StorageAccessRules.includeHiddenFilesystem(
            intent.getBooleanExtra("include_hidden_filesystem", false) ||
                prefs.getBoolean("always_show_hidden", false),
            MediaActions.hasAllFilesAccess(this)
        )

    private fun openDetail(item: MediaItem, position: Int) {
        openDetail(item, position, false)
    }

    private fun openDetail(item: MediaItem, position: Int, shuffleMode: Boolean) {
        savedFirstVisible = layoutManager.findFirstVisibleItemPosition()
        val intent = Intent(this, DetailActivity::class.java).apply {
            putExtra("uri", item.uri.toString())
            putExtra("name", item.name)
            putExtra("mime", item.mimeType)
            putExtra("path", item.relativePath)
            putExtra("album_key", albumKey)
            putExtra("position", position)
            putExtra("shuffle_mode", shuffleMode)
            putExtra("include_hidden_filesystem", shouldIncludeHiddenFilesystem())
            this@AlbumMediaActivity.intent
                .getStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS)
                ?.let { putStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS, it) }
        }
        startActivityForResult(intent, REQ_DETAIL)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) {
            if (resultCode == RESULT_OK) {
                MediaStoreRepository.invalidateCache()
                GalleryCatalogStore.markCatalogDirty(applicationContext)
                Ui.toast(this, getString(R.string.album_item_deleted))
            }
            loadMedia(true)
        } else if (requestCode == REQ_DETAIL && resultCode == RESULT_OK && data != null) {
            val removed = data.getStringArrayListExtra(MediaOperationNavigation.EXTRA_REMOVED_URIS).orEmpty()
            val moved = data.getStringArrayListExtra(MediaOperationNavigation.EXTRA_MOVED_URIS).orEmpty()
            completedRemovalUris.addAll((removed + if (albumKey == "all_media") emptyList() else moved).map(MediaIdentityRules::canonicalKey))
            adapter.removeCompletedItems(removed + if (albumKey == "all_media") emptyList() else moved)
            updateEmptyState()
            val destinationKey = data.getStringExtra(MediaOperationNavigation.EXTRA_DESTINATION_KEY)
            if (destinationKey != null) {
                openMoveDestination(destinationKey, data.getStringExtra(MediaOperationNavigation.EXTRA_DESTINATION_NAME) ?: destinationKey)
            } else {
                loadMedia(true)
            }
        }
    }

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else Ui.dp(this, 24)
    }

    private fun navigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else Ui.dp(this, 10)
    }

    companion object {
        private const val TAG_ALBUM_TOOLBAR = "album_toolbar"
        private const val TAG_ALBUM_SEARCH_TITLE = "album_search_title"
        private const val REQ_DELETE = 11
        private const val REQ_DETAIL = 12
        private const val MAX_GRID_SPACING_DP = 8
        private const val PREF_GRID_COLUMNS = "media_grid_columns"
        private const val INITIAL_MEDIA_DELAY_MS = 60L
        private const val GRID_POOL_WARMUP_STEP_MS = 24L
        private const val GRID_POOL_WARMUP_RETRY_MS = 80L
        private const val GRID_DENSITY_EXIT_MS = 70L
        private const val GRID_DENSITY_ENTRY_MS = 125L
        private const val FAST_SCROLL_PREVIEW_DURATION_MS = 650L
        private const val MEDIA_OBSERVER_GRACE_MS = 3_000L
        private const val MEDIA_REFRESH_DEBOUNCE_MS = 5_000L
        private const val GROUP_NONE = "none"
        private const val GROUP_TYPE = "type"
        private const val GROUP_EXTENSION = "extension"
        private const val GROUP_DAY = "day"
        private const val GROUP_MONTH = "month"
    }
}
