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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class AlbumMediaActivity : ComponentActivity() {
    internal val operations = ActivityOperationRunner(this)
    internal lateinit var adapter: MediaRecyclerAdapter
    internal lateinit var grid: AccessibleRecyclerView
    internal lateinit var fastScroller: AlbumFastScroller
    internal var scrollDateOverlay: ScrollDateOverlay? = null
    internal var albumSidebar: AlbumSidebar? = null
    internal lateinit var swipeRefresh: SwipeRefreshLayout
    internal lateinit var layoutManager: GridLayoutManager
    internal lateinit var searchInput: EditText
    private lateinit var searchBox: LinearLayout
    private lateinit var selectAllChip: TextView
    internal lateinit var moreButton: ImageButton
    private lateinit var emptyView: TextView
    private lateinit var catalogLoadingView: LoadingIndicatorView
    internal var albumKey: String? = null
    private var albumName: String = ""
    internal lateinit var prefs: SharedPreferences
    internal lateinit var cinemaPreferences: CinemaModePreferences
    internal var gridSpacingDp = 3
    private var gridColumnCount = 0
    private val gridGestureController = AlbumGridGestureController()
    private var gridTouchDownX = 0f
    private var gridTouchDownY = 0f
    private var gridTouchClickCandidate = false
    private val gridTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private var gridDensityAnimationGeneration = 0
    private var dragging = false
    private val selectionDragActive: Boolean get() = gridGestureController.selectionActive
    private var dragPosition = -1
    private var savedFirstVisible = 0
    private var draggedView: View? = null
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionActions: LinearLayout
    private lateinit var selectionActionDock: LinearLayout
    private lateinit var browseBar: GalleryBrowseBar
    private var appliedThemeBackground: Int? = null
    private lateinit var selectAllText: TextView
    private var spacingDecoration: RecyclerView.ItemDecoration? = null
    private var dragMoved = false
    internal var showImages = true
    internal var showVideos = true
    internal var showGifs = true
    internal var showRaw = true
    internal var showSvgs = true
    internal var listMode = false
    internal var groupMode = GROUP_NONE
    internal var mediaSortMode = MediaSortRules.SORT_CUSTOM
    internal var mediaSortDescending = true
    internal var choosingAlbumCover = false
    internal lateinit var catalogController: AlbumMediaCatalogController
    internal lateinit var selectionCoordinator: AlbumSelectionActions
    private var pendingPagedScrollPosition = -1
    private var gridScrollState = RecyclerView.SCROLL_STATE_IDLE
    private var pendingPagingData: PagingData<MediaItem>? = null
    private var pendingMediaRefresh = false
    private var mediaRefreshScheduled = false
    private var albumScreenResumed = false
    private var gridPoolWarmupRemaining = 0
    private var warmedGridPoolViewType = -1
    private var firstResume = true
    internal val completedRemovalUris = hashSetOf<String>()
    internal var pendingDeletePermanent = false
    private var folderObserver: android.os.FileObserver? = null
    private val createdAtElapsedRealtime = SystemClock.elapsedRealtime()
    private val searchHandler = Handler(Looper.getMainLooper())
    private val searchReload = Runnable {
        if (!isFinishing) loadMedia(false)
    }
    internal val mediaRefreshHandler = Handler(Looper.getMainLooper())
    private val mediaRefreshRunnable = Runnable {
        mediaRefreshScheduled = false
        if (!isFinishing && !isDestroyed) {
            if (!albumScreenResumed) {
                pendingMediaRefresh = true
                return@Runnable
            }
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
            val viewType = adapter.currentViewType()
            grid.recycledViewPool.putRecycledView(adapter.createViewHolder(grid, viewType))
            gridPoolWarmupRemaining--
            if (gridPoolWarmupRemaining > 0) grid.postDelayed(this, GRID_POOL_WARMUP_STEP_MS)
        }
    }
    private val finishFastScrollPreview = Runnable {
        if (::adapter.isInitialized) adapter.setFastScrollPreview(false)
    }
    private val initialMediaLoad = Runnable {
        if (!isFinishing && !isDestroyed) loadMedia(false)
    }
    private val mediaObserver = object : ContentObserver(mediaRefreshHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (isFinishing || isDestroyed) return
            if (albumScreenResumed) scheduleMediaRefresh() else pendingMediaRefresh = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        completedRemovalUris.addAll(savedInstanceState?.getStringArrayList("completed_removal_uris").orEmpty())
        pendingDeletePermanent = savedInstanceState?.getBoolean("pending_delete_permanent") ?: false
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE)
        cinemaPreferences = CinemaModePreferences(prefs)
        Ui.applySystemBars(this)
        catalogController = AlbumMediaCatalogController(applicationContext)
        selectionCoordinator = AlbumSelectionActions(this, prefs)
        albumKey = intent.getStringExtra("album_key")
        // A secure folder opens only after its password; any other way in (another app, a shortcut) is refused.
        if (albumKey?.let { SecurityStore(this).isSecureFolder(it) && !SecuritySession.isFolderUnlocked(it) } == true) {
            Ui.toast(this, getString(R.string.security_folder_locked))
            finish()
            return
        }
        albumName = intent.getStringExtra("album_name")?.takeIf { it.isNotEmpty() }
            ?: getString(R.string.album_default_name)
        readAlbumOptions()
        buildLayout()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleToolbarBack()
        })
        registerMediaObserver()
        if (MediaStoreRepository.isPhysicalAlbum(albumKey) && MediaActions.hasAllFilesAccess(this)) {
            val storage = Environment.getExternalStorageDirectory()
            val directory = java.io.File(storage, albumKey!!)
            if (runCatching { directory.canonicalPath.startsWith(storage.canonicalPath + java.io.File.separator) }.getOrDefault(false)) {
                folderObserver = object : android.os.FileObserver(directory.absolutePath,
                    CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE or ATTRIB or DELETE_SELF or MOVE_SELF) {
                    override fun onEvent(event: Int, path: String?) {
                        mediaRefreshHandler.post { if (!isFinishing && !isDestroyed) scheduleMediaRefresh() }
                    }
                }.also { it.startWatching() }
            }
        }
        mediaRefreshHandler.postDelayed(initialMediaLoad, INITIAL_MEDIA_DELAY_MS)
    }

    override fun onResume() {
        super.onResume()
        albumScreenResumed = true
        if (::swipeRefresh.isInitialized && !adapter.isSelectionMode()) swipeRefresh.isEnabled = pullToRefreshEnabled()
        if (::adapter.isInitialized) {
            adapter.refreshDisplayPreferences()
            if (adapter.currentViewType() != (if (listMode) 1 else if (horizontalGridEnabled()) 2 else 0)) applyViewMode()
        }
        Ui.applySystemBars(this)
        applyBrowseTheme()
        if (firstResume) {
            firstResume = false
            if (pendingMediaRefresh) scheduleMediaRefresh()
            return
        }
        if (::adapter.isInitialized) {
            if (pendingMediaRefresh) {
                GalleryCatalogStore.markCatalogDirty(applicationContext)
                MediaStoreRepository.invalidateCache()
                pendingMediaRefresh = false
            }
            adapter.refreshChangedThumbnails()
            loadMedia(true)
        }
    }

    override fun onDestroy() {
        if (::adapter.isInitialized) adapter.cancelPendingUpdates()
        operations.close()
        folderObserver?.stopWatching()
        searchHandler.removeCallbacks(searchReload)
        if (::grid.isInitialized) grid.removeCallbacks(gridPoolWarmup)
        if (::grid.isInitialized) grid.removeCallbacks(finishFastScrollPreview)
        super.onDestroy()
        mediaRefreshHandler.removeCallbacks(initialMediaLoad)
        mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
        try {
            contentResolver.unregisterContentObserver(mediaObserver)
        } catch (_: Exception) {
        }
        catalogController.close()
        selectionCoordinator.close()
    }

    override fun onPause() {
        albumScreenResumed = false
        if (mediaRefreshScheduled) pendingMediaRefresh = true
        mediaRefreshScheduled = false
        mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
        super.onPause()
    }

    private fun refreshCatalogWithWorker() {
        if (MediaStoreRepository.isPhysicalAlbum(albumKey)) {
            GalleryCatalogStore.markCatalogDirty(applicationContext)
            MediaStoreRepository.invalidateCache()
            loadMedia(true)
            return
        }
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
        if (isFinishing || isDestroyed) return
        mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
        mediaRefreshScheduled = true
        val age = SystemClock.elapsedRealtime() - createdAtElapsedRealtime
        val delay = max(MEDIA_REFRESH_DEBOUNCE_MS, MEDIA_OBSERVER_GRACE_MS - age)
        mediaRefreshHandler.postDelayed(mediaRefreshRunnable, delay)
    }

    private val systemBars by lazy { SystemBarInsets(this) }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(this@AlbumMediaActivity))
        }

        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            tag = TAG_ALBUM_TOOLBAR
            setPadding(Ui.dp(this@AlbumMediaActivity, 8), systemBars.top + Ui.dp(this@AlbumMediaActivity, 6), Ui.dp(this@AlbumMediaActivity, 8), Ui.dp(this@AlbumMediaActivity, 6))
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
                        adapter.applyFilterAsync(s?.toString().orEmpty()) { updateEmptyState() }
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
                if (choosingAlbumCover) {
                    choosingAlbumCover = false
                    searchInput.isEnabled = true
                    updateSearchPresentation()
                } else if (adapter.isSelectionMode()) {
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
                    if (choosingAlbumCover) {
                        val item = adapter.getItem(position)
                        prefs.edit().putString(
                            AlbumCoverRules.preferenceKey(albumKey ?: "all_media"),
                            item.uri.toString()
                        ).apply()
                        choosingAlbumCover = false
                        searchInput.isEnabled = true
                        updateSearchPresentation()
                        Haptics.perform(this@AlbumMediaActivity, Haptics.Effect.CONFIRM)
                        Ui.toast(this@AlbumMediaActivity, getString(R.string.album_cover_saved))
                    } else if (adapter.isSelectionMode()) {
                        adapter.toggleSelection(position)
                        Haptics.perform(this@AlbumMediaActivity, Haptics.Effect.TICK)
                        updateSelectionUi()
                    } else {
                        openDetail(adapter.getItem(position), position)
                    }
                }
            }

            override fun onMediaPreview(position: Int) {
                if (position in 0 until adapter.getCount()) {
                    openDetail(adapter.getItem(position), position)
                }
            }

            override fun onMediaLongClick(view: View, position: Int): Boolean {
                if (choosingAlbumCover) return true
                if (position !in 0 until adapter.getCount()) return true
                val wasSelectionMode = adapter.isSelectionMode()
                val wasSelected = adapter.isSelected(position)
                if (adapter.isPagingMode()) {
                    if (!adapter.isSelectionMode()) enterSelectionMode()
                    if (!adapter.isSelected(position)) adapter.selectPosition(position)
                    if (!wasSelectionMode || !wasSelected) beginSelectionDrag(position)
                    updateSelectionUi()
                    Ui.longPressFeedback(view)
                    return true
                }
                if (!adapter.isSelectionMode()) {
                    enterSelectionMode()
                    adapter.selectPosition(position)
                    beginSelectionDrag(position)
                    updateSelectionUi()
                    Ui.longPressFeedback(view)
                    return true
                }
                if (!adapter.isSelected(position)) {
                    adapter.selectPosition(position)
                    beginSelectionDrag(position)
                    updateSelectionUi()
                    Ui.longPressFeedback(view)
                    return true
                }
                Ui.longPressFeedback(view, shrink = false)
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
                catalogLoadingView.visibility = View.GONE
                emptyView.setText(R.string.album_load_failed)
                emptyView.visibility = if (adapter.getCount() == 0) View.VISIBLE else View.GONE
            }
        }
        applyViewMode()
        grid.adapter = adapter
        grid.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(recyclerView: RecyclerView, event: MotionEvent): Boolean {
                if (shouldHandleGridGesture(event)) {
                    handleGridGesture(event)
                    return true
                }
                return false
            }

            override fun onTouchEvent(recyclerView: RecyclerView, event: MotionEvent) {
                handleGridGesture(event)
            }
        })
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
            if (!dragging && !selectionDragActive) {
                if (event.actionMasked == MotionEvent.ACTION_UP && gridTouchClickCandidate) grid.performClick()
                if (event.actionMasked == MotionEvent.ACTION_UP) gridTouchClickCandidate = false
                return@setOnTouchListener false
            }
            false
        }
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Ui.accent(this@AlbumMediaActivity))
            setProgressBackgroundColorSchemeColor(Ui.surface(this@AlbumMediaActivity))
            isEnabled = pullToRefreshEnabled()
            setOnRefreshListener {
                refreshCatalogWithWorker()
            }
        }
        swipeRefresh.addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(swipeRefresh, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        fastScroller = AlbumFastScroller(this, grid) { active ->
            grid.removeCallbacks(finishFastScrollPreview)
            onScrollDateDrag(active)
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
        installScrollDates(content)
        installAlbumSidebarGesture()

        emptyView = Ui.label(this, getString(R.string.album_empty)).apply {
            visibility = View.GONE
        }
        content.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        catalogLoadingView = LoadingIndicatorView(this, description = getString(R.string.album_loading_media)).apply { visibility = View.GONE }
        content.addView(catalogLoadingView, FrameLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32), Gravity.CENTER))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        browseBar = GalleryBrowseBar(this, systemBars.bottom,
            onSort = ::showMediaSortDialog,
            onOrganize = {
                startActivity(OrganizerActivity.intent(this,
                    intent.getStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS),
                    shouldIncludeHiddenFilesystem(), albumKey))
            },
            onSettings = { startActivity(Intent(this, SettingsActivity::class.java)) },
            onRandomPlayback = ::startRandomPlayback)
        browseBar.findViewWithTag<View>("browse_shuffle").apply {
            // Trashed media does not offer random playback in the album menu either.
            isEnabled = albumKey != VirtualAlbumRules.TRASH_KEY
            alpha = if (isEnabled) 1f else 0.4f
        }
        root.addView(browseBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        selectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.surface(this@AlbumMediaActivity))
            setPadding(0, Ui.dp(this@AlbumMediaActivity, 1), 0, systemBars.bottom)
        }
        systemBars.onChange {
            bar.setPadding(Ui.dp(this, 8), systemBars.top + Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6))
            browseBar.setBottomInset(systemBars.bottom)
            selectionActions.setPadding(0, Ui.dp(this, 1), 0, systemBars.bottom)
        }
        selectionActionDock = Ui.selectionActionDock(this)
        selectionActions.addView(selectionActionDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (albumKey == VirtualAlbumRules.TRASH_KEY) {
            addSelectionAction(R.drawable.ic_restore, getString(R.string.action_restore)) { restoreSelected() }
            addSelectionAction(R.drawable.ic_trash, getString(R.string.action_delete_permanently)) { SecurityGate.requireFileOperation(this) { confirmDeleteSelected() } }
        } else {
            addSelectionAction(R.drawable.ic_share, getString(R.string.action_share)) { shareSelected() }
            addSelectionAction(R.drawable.ic_heart, getString(R.string.action_favorite)) { favoriteSelected() }
            addSelectionAction(R.drawable.ic_trash, getString(R.string.action_delete)) { SecurityGate.requireFileOperation(this) { confirmDeleteSelected() } }
            addSelectionAction(R.drawable.ic_arrow_right, getString(R.string.action_move)) { SecurityGate.requireFileOperation(this) { askMoveSelected() } }
        }
        selectionActions.visibility = View.GONE
        root.addView(selectionActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun warmGridPoolGradually() {
        if (!::grid.isInitialized || adapter.getCount() == 0) return
        val viewType = adapter.currentViewType()
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

    private fun applyBrowseTheme() {
        scrollDateOverlay?.restyle()
        val color = Ui.bg(this)
        if (appliedThemeBackground == color || !::browseBar.isInitialized) return
        appliedThemeBackground = color
        findViewById<ViewGroup>(android.R.id.content).getChildAt(0).setBackgroundColor(color)
        grid.setBackgroundColor(color)
        searchInput.setTextColor(Ui.text(this))
        searchInput.setHintTextColor(Ui.muted(this))
        val toolbar = window.decorView.findViewWithTag<ViewGroup>(TAG_ALBUM_TOOLBAR)
        for (index in 0 until toolbar.childCount) {
            (toolbar.getChildAt(index) as? ImageButton)?.setColorFilter(Ui.text(this))
        }
        swipeRefresh.setColorSchemeColors(Ui.accent(this))
        swipeRefresh.setProgressBackgroundColorSchemeColor(Ui.surface(this))
        browseBar.restyle()
        selectionActions.setBackgroundColor(Ui.surface(this))
        Ui.restyleSelectionActionDock(selectionActionDock)
        updateSelectionUi()
        // Theme changes affect every visible cell, without invalidating the list structure.
        adapter.notifyItemRangeChanged(0, adapter.itemCount)
    }

    private fun readAlbumOptions() {
        showImages = prefs.getBoolean(optionKey(GalleryPreferences.FILTER_IMAGES), true)
        showVideos = prefs.getBoolean(optionKey(GalleryPreferences.FILTER_VIDEOS), true)
        showGifs = prefs.getBoolean(optionKey(GalleryPreferences.FILTER_GIFS), true)
        showRaw = prefs.getBoolean(optionKey(GalleryPreferences.FILTER_RAW), true)
        showSvgs = prefs.getBoolean(optionKey(GalleryPreferences.ALBUM_FILTER_SVG), true)
        listMode = prefs.getBoolean(optionKey("list_mode"), false)
        gridColumnCount = GridColumnRules.normalized(
            prefs.getInt(PREF_GRID_COLUMNS, 0),
            mediaSpanCount()
        )
        groupMode = prefs.getString(optionKey("group_mode"), GROUP_NONE) ?: GROUP_NONE
        val chronological = albumKey == VirtualAlbumRules.RECENT_KEY || albumKey == VirtualAlbumRules.TRASH_KEY
        val defaultSort = if (chronological) MediaSortRules.SORT_DATE else MediaSortRules.SORT_CUSTOM
        mediaSortMode = prefs.getString(optionKey(GalleryPreferences.SORT_MODE), defaultSort) ?: defaultSort
        mediaSortDescending = prefs.getBoolean(optionKey(GalleryPreferences.SORT_DESC), true)
    }

    internal fun applyGridSpacing() {
        if (!::grid.isInitialized) return
        val gap = Ui.dp(this, gridSpacingDp)
        spacingDecoration?.let(grid::removeItemDecoration)
        spacingDecoration = GridSpacingItemDecoration(gap, layoutManager.spanCount, horizontalGridEnabled()).also(grid::addItemDecoration)
        grid.setPadding(0, 0, 0, Ui.dp(this, 16))
        grid.invalidateItemDecorations()
        updateThumbnailRequestSize()
    }

    /** "Atualizar ao puxar a tela". */
    private fun pullToRefreshEnabled(): Boolean = GalleryPreferences.flag(this, GalleryPreferences.PULL_TO_REFRESH)

    private fun enterSelectionMode() {
        adapter.setSelectionMode(true)
    }

    internal fun exitSelectionMode() {
        val wasSelecting = adapter.isSelectionMode()
        gridGestureController.endSelection()
        if (::swipeRefresh.isInitialized) swipeRefresh.isEnabled = pullToRefreshEnabled()
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
        browseBar.visibility = if (active) View.GONE else View.VISIBLE
        selectAllChip.visibility = if (active) View.VISIBLE else View.GONE
        Ui.styleSelectionToggle(selectAllChip, adapter.allVisibleSelected())
        searchInput.isEnabled = !active
        moreButton.setImageResource(if (active) R.drawable.ic_close else R.drawable.ic_more_vertical)
        moreButton.contentDescription = getString(
            if (active) R.string.action_cancel_selection else R.string.action_more_options
        )
        if (active) {
            searchInput.clearFocus()
            hideKeyboard()
        }
        updateSearchPresentation()
    }

    private fun beginSelectionDrag(position: Int) {
        gridGestureController.beginSelection(position)
        swipeRefresh.isEnabled = false
        grid.parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun handleToolbarBack() {
        if (choosingAlbumCover) {
            choosingAlbumCover = false
            searchInput.isEnabled = true
            updateSearchPresentation()
            return
        }
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

    internal fun updateSearchPresentation() {
        if (!::searchInput.isInitialized || !::searchBox.isInitialized) return
        val selecting = ::adapter.isInitialized && adapter.isSelectionMode() && adapter.selectedCount() > 0
        val searching = !selecting && (searchInput.hasFocus() || searchInput.text.isNotEmpty())

        searchBox.background = when {
            selecting -> Ui.rounded(Ui.surface(this), 18, this)
            searching -> Ui.rounded(Ui.search(this), 18, this)
            else -> Ui.rounded(Ui.search(this), 18, this)
        }
        searchInput.hint = when {
            choosingAlbumCover -> getString(R.string.album_choose_cover_hint)
            selecting -> getString(R.string.album_selected_of_total, adapter.selectedCount(), adapter.getCount())
            searching -> getString(R.string.album_search_folder)
            else -> getString(R.string.album_search_in, albumName)
        }
        Ui.styleSelectionCounter(searchInput, selecting, idleTextSizeSp = if (searching) 14f else 15f)
        if (choosingAlbumCover) searchInput.setHintTextColor(Ui.text(this))
        searchInput.isCursorVisible = searching && searchInput.hasFocus()

        val searchIcon = if (selecting) null else getDrawable(R.drawable.ic_search)?.mutate()?.apply {
            setTint(Ui.muted(this@AlbumMediaActivity))
        }
        searchInput.setCompoundDrawablesRelativeWithIntrinsicBounds(searchIcon, null, null, null)
    }

    internal fun hideKeyboard() {
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

    internal fun loadMedia(preserveScroll: Boolean) {
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
        if (adapter.getCount() == 0 && ::emptyView.isInitialized) {
            emptyView.visibility = View.GONE
            catalogLoadingView.visibility = View.VISIBLE
        }
        if (usePaging) {
            pendingPagedScrollPosition = targetPosition
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
            },
            onFailure = {
                if (!isFinishing && !isDestroyed) {
                    swipeRefresh.isRefreshing = false
                    catalogLoadingView.visibility = View.GONE
                    if (adapter.getCount() == 0) {
                        emptyView.setText(R.string.album_load_failed)
                        emptyView.visibility = View.VISIBLE
                    } else Ui.toast(this, getString(R.string.album_load_failed))
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
        if (albumKey == VirtualAlbumRules.TRASH_KEY && items.isEmpty() && query.isBlank()) {
            finish()
            return
        }
        // Drop the temporary exclusion once a fresh delivery confirms the item has left.
        // If it is later moved back into this folder it must become visible again.
        completedRemovalUris.retainAll(items.mapTo(HashSet()) { MediaIdentityRules.canonicalKey(it.uri.toString()) })
        adapter.submitAsync(items.filterNot(::wasRemoved), query) {
            if (!isFinishing && !isDestroyed) {
                updateEmptyState()
                updateSelectionUi()
                if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
                if (targetPosition > 0) grid.scrollToPosition(min(targetPosition, max(0, adapter.getCount() - 1)))
            }
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
        outState.putBoolean("pending_delete_permanent", pendingDeletePermanent)
        super.onSaveInstanceState(outState)
    }

    internal fun spacingKey(): String = "grid_spacing_global"

    private fun gridGestureRoute(event: MotionEvent): AlbumGridGestureController.Route =
        gridGestureController.route(
            pointerDown = event.actionMasked == MotionEvent.ACTION_POINTER_DOWN,
            pointers = event.pointerCount,
            listMode = listMode,
            reordering = dragging
        )

    private fun shouldHandleGridGesture(event: MotionEvent): Boolean =
        gridGestureRoute(event) != AlbumGridGestureController.Route.SCROLL

    private fun handleGridGesture(event: MotionEvent) {
        if (gridGestureRoute(event) == AlbumGridGestureController.Route.PINCH) {
            handleGridPinch(event)
            return
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val targetView = grid.findChildViewUnder(event.x, event.y)
                val target = targetView?.let(grid::getChildAdapterPosition) ?: RecyclerView.NO_POSITION
                if (selectionDragActive) {
                    if (gridGestureController.selectThrough(target, adapter::selectPosition)) {
                        Haptics.perform(grid, Haptics.Effect.TICK)
                        updateSelectionUi()
                    }
                    val edge = Ui.dp(this, SELECTION_DRAG_EDGE_DP)
                    when {
                        event.y < edge -> grid.scrollBy(0, -Ui.dp(this, SELECTION_DRAG_SCROLL_DP))
                        event.y > grid.height - edge -> grid.scrollBy(0, Ui.dp(this, SELECTION_DRAG_SCROLL_DP))
                    }
                } else if (dragging && target >= 0 && target != dragPosition) {
                    if (adapter.isSelectionMode() && adapter.moveSelectedBlock(target)) {
                        dragPosition = target
                        dragMoved = true
                        animateGridMove()
                    } else if (!adapter.isSelectionMode() && adapter.moveVisible(dragPosition, target)) {
                        dragPosition = target
                        dragMoved = true
                        animateGridMove()
                    }
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (selectionDragActive) {
                    gridGestureController.endSelection()
                    grid.parent?.requestDisallowInterceptTouchEvent(false)
                    swipeRefresh.isEnabled = pullToRefreshEnabled()
                    updateSelectionUi()
                } else if (dragging) {
                    finishDrag()
                    if (dragMoved) {
                        saveCustomOrder()
                        if (adapter.isSelectionMode()) exitSelectionMode()
                    }
                }
            }
        }
    }

    private fun handleGridPinch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (listMode || dragging || event.pointerCount < 2) return
                gridGestureController.beginPinch(horizontalPointerSpan(event))
                grid.stopScroll()
                grid.parent?.requestDisallowInterceptTouchEvent(true)
                if (::swipeRefresh.isInitialized) swipeRefresh.isEnabled = false
            }
            MotionEvent.ACTION_MOVE -> if (gridGestureController.pinchActive) {
                val delta = gridGestureController.movePinch(horizontalPointerSpan(event))
                if (delta != 0) changeGridColumnCount(
                    delta,
                    horizontalPointerCenterX(event),
                    horizontalPointerCenterY(event)
                )
            }
            MotionEvent.ACTION_POINTER_UP -> {
                gridGestureController.pointerUp()
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                gridGestureController.endPinch()
                grid.parent?.requestDisallowInterceptTouchEvent(false)
                if (::swipeRefresh.isInitialized) swipeRefresh.isEnabled = pullToRefreshEnabled()
            }
        }
    }

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

    internal fun updateEmptyState() {
        catalogLoadingView.visibility = View.GONE
        emptyView.setText(R.string.album_empty)
        emptyView.visibility = if (adapter.getCount() == 0) View.VISIBLE else View.GONE
    }

    internal fun finishIfAlbumEmpty() {
        val key = albumKey ?: return
        fun returnToAlbums() {
            if (isFinishing || isDestroyed) return
            startActivity(Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            })
            finish()
        }
        // A search, type filter or one paging page must not decide whether an album is empty.
        lifecycleScope.launch {
            val empty = withContext(Dispatchers.IO) {
                if (!VirtualAlbumRules.isVirtual(key) && key != "all_media") {
                    MediaOperationNavigation.isEmptyMediaFolder(File(Environment.getExternalStorageDirectory(), key))
                } else {
                    MediaStoreRepository.loadMediaForAlbum(applicationContext, key, shouldIncludeHiddenFilesystem()).isEmpty()
                }
            }
            if (empty && !isFinishing) returnToAlbums()
        }
    }

    internal fun startRandomPlayback() {
        if (adapter.getCount() == 0) {
            Ui.toast(this, getString(R.string.album_no_media_to_play))
            return
        }
        val position = Random().nextInt(adapter.getCount())
        openDetail(adapter.getItem(position), position, true)
    }

    internal fun applyViewMode() {
        if (!::grid.isInitialized || !::adapter.isInitialized) return
        gridDensityAnimationGeneration++
        grid.animate().cancel()
        grid.alpha = 1f
        grid.scaleX = 1f
        grid.scaleY = 1f
        layoutManager.spanCount = if (listMode) 1 else gridColumnCount
        val horizontal = horizontalGridEnabled()
        layoutManager.orientation = if (horizontal) RecyclerView.HORIZONTAL else RecyclerView.VERTICAL
        adapter.setListMode(listMode)
        adapter.setHorizontalGrid(horizontal)
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
        val entryScale = if (delta > 0) 1.018f else 0.982f
        grid.animate().cancel()
        grid.pivotX = focusX.coerceIn(0f, grid.width.toFloat())
        grid.pivotY = focusY.coerceIn(0f, grid.height.toFloat())
        layoutManager.spanCount = gridColumnCount
        applyGridSpacing()
        updateThumbnailRequestSize()
        if (anchorPosition != RecyclerView.NO_POSITION) {
            layoutManager.scrollToPositionWithOffset(anchorPosition, anchorOffset)
        }
        grid.alpha = 0.82f
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

    private fun updateThumbnailRequestSize() {
        if (!::adapter.isInitialized || !::layoutManager.isInitialized) return
        val size = if (listMode) {
            Ui.dp(this, 82)
        } else {
            val spans = max(1, layoutManager.spanCount)
            val totalGaps = Ui.dp(this, gridSpacingDp) * max(0, spans - 1)
            val across = if (horizontalGridEnabled()) {
                grid.height.takeIf { it > 0 } ?: (resources.displayMetrics.heightPixels * 2 / 3)
            } else resources.displayMetrics.widthPixels
            max(Ui.dp(this, 96), (across - totalGaps) / spans)
        }
        adapter.setGridThumbnailSize(size)
    }

    private fun horizontalGridEnabled(): Boolean =
        !listMode && GalleryPreferences.flag(this, GalleryPreferences.HORIZONTAL_THUMBNAIL_SCROLL)

    private fun mediaSpanCount(): Int =
        max(2, resources.displayMetrics.widthPixels / Ui.dp(this, 126))

    internal fun optionKey(suffix: String): String = AlbumCoverRules.optionKey(albumKey, suffix)

    internal fun folderDisplayPath(): String {
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

    internal fun createFolder(rawName: String) {
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
            Haptics.perform(this, Haptics.Effect.REJECT)
            Ui.toast(this, getString(R.string.album_folder_create_failed))
        }
    }

    internal fun requestFileManagementAccess() {
        Ui.showConfirmationDialog(
            this,
            getString(R.string.access_full_management_title),
            getString(R.string.access_batch_operation_explanation),
            getString(R.string.action_allow)
        ) { MediaActions.requestAllFilesAccess(this) }
    }

    internal fun shouldIncludeHiddenFilesystem(): Boolean =
        StorageAccessRules.includeHiddenFilesystem(
            intent.getBooleanExtra("include_hidden_filesystem", false) ||
                GalleryPreferences.hiddenFlag(this, "always_show_hidden"),
            MediaActions.hasAllFilesAccess(this)
        )

    private fun openDetail(item: MediaItem, position: Int) {
        openDetail(item, position, false)
    }

    private fun openDetail(item: MediaItem, position: Int, shuffleMode: Boolean) {
        if (operations.busy) return
        savedFirstVisible = layoutManager.findFirstVisibleItemPosition()
        val intent = ViewerLaunch.internal(this, Intent(this, DetailActivity::class.java)).apply {
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
        detailLauncher.launch(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) {
            if (resultCode == RESULT_OK) {
                MediaStoreRepository.invalidateCache()
                GalleryCatalogStore.markCatalogDirty(applicationContext)
                Ui.toast(
                    this,
                    getString(
                        if (pendingDeletePermanent) R.string.album_item_deleted
                        else R.string.item_moved_to_trash
                    )
                )
            }
            loadMedia(true)
            if (resultCode == RESULT_OK) finishIfAlbumEmpty()
        } else if (requestCode == REQ_RESTORE) {
            if (resultCode == RESULT_OK) {
                MediaStoreRepository.invalidateCache()
                GalleryCatalogStore.markCatalogDirty(applicationContext)
                Ui.toast(this, getString(R.string.item_restored))
            }
            loadMedia(true)
        }
    }

    // What the viewer removed or moved, so the grid updates without reloading everything.
    private val detailLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) return@registerForActivityResult
        val removed = data.getStringArrayListExtra(MediaOperationNavigation.EXTRA_REMOVED_URIS).orEmpty()
        val moved = data.getStringArrayListExtra(MediaOperationNavigation.EXTRA_MOVED_URIS).orEmpty()
        val movedOutsideCollection = if (VirtualAlbumRules.remainsAfterMove(albumKey)) emptyList() else moved
        completedRemovalUris.addAll((removed + movedOutsideCollection).map(MediaIdentityRules::canonicalKey))
        adapter.removeCompletedItems(removed + movedOutsideCollection)
        updateEmptyState()
        val destinationKey = data.getStringExtra(MediaOperationNavigation.EXTRA_DESTINATION_KEY)
        if (destinationKey != null) {
            openMoveDestination(destinationKey, data.getStringExtra(MediaOperationNavigation.EXTRA_DESTINATION_NAME) ?: destinationKey)
        } else {
            loadMedia(true)
            if (removed.isNotEmpty()) finishIfAlbumEmpty()
        }
    }

    companion object {
        private const val TAG_ALBUM_TOOLBAR = "album_toolbar"
        private const val TAG_ALBUM_SEARCH_TITLE = "album_search_title"
        internal const val REQ_DELETE = 11
        internal const val REQ_RESTORE = 15
        internal const val MAX_GRID_SPACING_DP = 8
        private const val PREF_GRID_COLUMNS = "media_grid_columns"
        private const val INITIAL_MEDIA_DELAY_MS = 60L
        private const val GRID_POOL_WARMUP_STEP_MS = 24L
        private const val GRID_POOL_WARMUP_RETRY_MS = 80L
        private const val GRID_DENSITY_ENTRY_MS = 125L
        private const val SELECTION_DRAG_EDGE_DP = 72
        private const val SELECTION_DRAG_SCROLL_DP = 24
        private const val FAST_SCROLL_PREVIEW_DURATION_MS = 650L
        private const val MEDIA_OBSERVER_GRACE_MS = 3_000L
        private const val MEDIA_REFRESH_DEBOUNCE_MS = 5_000L
        internal const val GROUP_NONE = "none"
        internal const val GROUP_TYPE = "type"
        internal const val GROUP_EXTENSION = "extension"
        internal const val GROUP_DAY = "day"
        internal const val GROUP_MONTH = "month"
    }
}
