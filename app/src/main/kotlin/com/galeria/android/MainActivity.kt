package com.galeria.android

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.graphics.drawable.toDrawable
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    internal lateinit var adapter: AlbumRecyclerAdapter
    private lateinit var emptyView: TextView
    private lateinit var catalogLoadingView: LoadingIndicatorView
    internal lateinit var searchInput: EditText
    private lateinit var grid: AccessibleRecyclerView
    internal lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var root: LinearLayout
    private lateinit var top: LinearLayout
    private lateinit var searchIconButton: ImageButton
    private lateinit var selectAllChip: TextView
    internal lateinit var moreButton: ImageButton
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionActions: LinearLayout
    private lateinit var selectionActionDock: LinearLayout
    internal lateinit var browseBar: GalleryBrowseBar
    internal var browsePanel: BrowseActionPanel? = null
    private lateinit var selectAllText: TextView
    internal lateinit var prefs: SharedPreferences
    private lateinit var scaleDetector: ScaleGestureDetector
    internal lateinit var accessCoordinator: MainMediaAccessCoordinator
    internal lateinit var catalogController: AlbumCatalogController
    internal var sortMode = SORT_MODIFIED
    internal var sortDesc = true
    internal var showImages = true
    internal var showVideos = true
    internal var showGifs = true
    internal var showRaw = true
    internal var showSvgs = true
    internal var showPortraits = false
    internal var showHiddenFolders = false
    internal var automaticallyHiddenKeys: Set<String> = emptySet()
    private var columnCount = 3
    // Read by the folder layout dialog; the setter stays setColumnCount(), which also relays out the grid.
    internal val currentColumnCount: Int get() = columnCount
    internal var albumSpacingDp = 8
    private var lastColumnGestureMs = 0L
    private var gridTouchDownX = 0f
    private var gridTouchDownY = 0f
    private var gridTouchClickCandidate = false
    private val gridTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    internal val mediaLoader = Executors.newSingleThreadExecutor()
    private var firstResume = true
    internal var mainScreenResumed = false
    private var mediaObserverRefreshPending = false
    private var mediaObserverRefreshScheduled = false
    private var deferredCatalogRefreshPending = false
    private var pendingAlbumSubmission: PendingAlbumSubmission? = null
    private var forceAlbumCoverRefreshOnNextSubmit = false
    internal var visibilityDialogAdapter: BaseAdapter? = null
    internal var refreshVisibilityDialogCounts: (() -> Unit)? = null
    internal var mergeVisibleAlbumsIntoDialog: ((List<AlbumItem>) -> Unit)? = null
    private val revealedFolderObservers = HashMap<String, android.os.FileObserver>()
    internal val visibilityFolderObservers = HashMap<String, android.os.FileObserver>()
    internal val visibilityCountRefreshRunnable = Runnable { refreshVisibilityDialogCounts?.invoke() }
    internal val mediaRefreshHandler = Handler(Looper.getMainLooper())
    private val revealExpiryRunnable = Runnable {
        visibilityDialogAdapter?.notifyDataSetChanged()
        if (mainScreenResumed && accessCoordinator.hasMediaLibraryAccess()) loadAlbums()
        scheduleRevealExpiry()
    }
    private val mediaRefreshRunnable = Runnable {
        mediaObserverRefreshScheduled = false
        if (!isFinishing && mainScreenResumed && hasWindowFocus() && accessCoordinator.hasMediaLibraryAccess()) {
            mediaObserverRefreshPending = false
            GalleryCatalogStore.markCatalogDirty(applicationContext)
            MediaStoreRepository.invalidateCache()
            loadAlbums()
        } else {
            mediaObserverRefreshPending = true
        }
    }
    private val mediaObserver = object : ContentObserver(mediaRefreshHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            if (isFinishing || isDestroyed) return
            mediaObserverRefreshPending = true
            if (mainScreenResumed && refreshVisibilityDialogCounts != null) {
                mediaRefreshHandler.removeCallbacks(visibilityCountRefreshRunnable)
                mediaRefreshHandler.postDelayed(visibilityCountRefreshRunnable, 180L)
            }
            if (mainScreenResumed && hasWindowFocus()) scheduleMediaRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && !isHiddenPanelHost()) TemporaryAlbumVisibility.clear()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        Ui.applySystemBars(this)
        accessCoordinator = MainMediaAccessCoordinator(this, prefs)
        catalogController = AlbumCatalogController(applicationContext)
        loadSettings()
        buildLayout()
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back undoes the last thing on screen first: selection, then the search. Only a clean album list closes.
                if (::adapter.isInitialized && adapter.isSelectionMode()) {
                    exitSelectionMode()
                    return
                }
                if (::searchInput.isInitialized && (searchInput.hasFocus() || searchInput.text.isNotEmpty())) {
                    searchInput.setText("")
                    searchInput.clearFocus()
                    (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                        .hideSoftInputFromWindow(searchInput.windowToken, 0)
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
        registerMediaObserver()
        accessCoordinator.start(::scheduleInitialCatalogLoad)
        if (isHiddenPanelHost()) root.post {
            if (!isFinishing && !isDestroyed) SecurityGate.requireHiddenAccess(this, action = ::showFolderVisibilityDialog)
        }
    }

    internal fun isHiddenPanelHost() = intent.getBooleanExtra(EXTRA_HIDDEN_PANEL, false)

    override fun onResume() {
        super.onResume()
        mainScreenResumed = true
        if (::swipeRefresh.isInitialized) swipeRefresh.isEnabled = GalleryPreferences.flag(this, GalleryPreferences.PULL_TO_REFRESH)
        scheduleRevealExpiry()
        if (firstResume) {
            firstResume = false
            return
        }
        loadSettings()
        applyThemeColors()
        if (::browseBar.isInitialized) refreshBrowseBar()
        if (::adapter.isInitialized) adapter.setShowAlbumNames(GalleryPreferences.flag(this, GalleryPreferences.SHOW_ALBUM_NAMES))
        refreshVisibilityDialogCounts?.invoke()
        if (::adapter.isInitialized) adapter.refreshChangedCovers()
        if (accessCoordinator.hasMediaLibraryAccess()) {
            if (GalleryCatalogStore.isCatalogDirty(applicationContext, shouldIncludeHiddenFilesystem())) {
                if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = true
                loadAlbums()
            } else {
                loadAlbums()
                if (mediaObserverRefreshPending) scheduleMediaRefresh()
            }
        } else if (accessCoordinator.initialChoiceMade()) {
            catalogLoadingView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            emptyView.setText(R.string.access_choose_in_settings)
        }
    }

    private fun scheduleInitialCatalogLoad() {
        mediaRefreshHandler.postDelayed({
            if (!isFinishing && accessCoordinator.hasMediaLibraryAccess()) {
                if (GalleryCatalogStore.isCatalogDirty(applicationContext, shouldIncludeHiddenFilesystem())) {
                    if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = true
                    loadAlbums()
                } else {
                    loadAlbums()
                }
            }
        }, INITIAL_CATALOG_DELAY_MS)
    }

    override fun onPause() {
        mainScreenResumed = false
        mediaRefreshHandler.removeCallbacks(revealExpiryRunnable)
        if (mediaObserverRefreshScheduled) mediaObserverRefreshPending = true
        mediaObserverRefreshScheduled = false
        mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && mainScreenResumed && ::accessCoordinator.isInitialized &&
            accessCoordinator.hasMediaLibraryAccess()) {
            if (mediaObserverRefreshPending) scheduleMediaRefresh()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
        mediaRefreshHandler.removeCallbacks(revealExpiryRunnable)
        if (!isChangingConfigurations && !isHiddenPanelHost()) TemporaryAlbumVisibility.clear()
        revealedFolderObservers.values.forEach { it.stopWatching() }
        revealedFolderObservers.clear()
        visibilityFolderObservers.values.forEach { it.stopWatching() }
        visibilityFolderObservers.clear()
        mediaRefreshHandler.removeCallbacks(visibilityCountRefreshRunnable)
        try {
            contentResolver.unregisterContentObserver(mediaObserver)
        } catch (_: Exception) {
        }
        catalogController.close()
        mediaLoader.shutdownNow()
    }

    private val systemBars by lazy { SystemBarInsets(this) }

    /** Shows the pull-to-refresh spinner once the list exists. */
    internal fun showRefreshing() {
        if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = true
    }

    private fun buildLayout() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(this@MainActivity))
        }

        top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.search(this@MainActivity), 22, this@MainActivity)
            Ui.setPadding(this, 8, 1, 5, 1)
        }
        val topParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44)).apply {
            setMargins(Ui.dp(this@MainActivity, 24), systemBars.top + Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 14))
        }
        root.addView(top, topParams)

        selectAllChip = Ui.title(this, "", 16).apply {
            gravity = Gravity.CENTER
            setTextColor(Ui.text(this@MainActivity))
            visibility = View.GONE
            setOnClickListener { toggleSelectAll() }
            Ui.styleSelectionToggle(this, false)
        }
        top.addView(selectAllChip, LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 30)).apply {
            marginStart = Ui.dp(this@MainActivity, 3)
            marginEnd = Ui.dp(this@MainActivity, 3)
        })

        searchIconButton = iconButton(R.drawable.ic_search).apply {
            setOnClickListener {
                searchInput.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(searchInput, 0)
            }
        }
        top.addView(searchIconButton, LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 38)))

        searchInput = EditText(this).apply {
            setHint(R.string.main_search_folders)
            setHintTextColor(0x99F5F7FA.toInt())
            setTextColor(Ui.text(this@MainActivity))
            textSize = 14f
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(Ui.dp(this@MainActivity, 8), 0, Ui.dp(this@MainActivity, 8), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    adapter.applyFilter(s?.toString().orEmpty())
                    updateEmptyText()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        top.addView(searchInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))

        moreButton = iconButton(R.drawable.ic_more_vertical).apply {
            contentDescription = getString(R.string.action_more_options)
            setOnClickListener {
                if (adapter.isSelectionMode()) {
                    exitSelectionMode()
                } else {
                    openBrowsePanel()
                }
            }
        }
        top.addView(moreButton, LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 38)))

        selectionBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.surface(this@MainActivity), 8, this@MainActivity)
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
            layoutManager = GridLayoutManager(this@MainActivity, columnCount).also { this@MainActivity.layoutManager = it }
            clipToPadding = false
            setHasFixedSize(true)
            setItemViewCacheSize(10)
            recycledViewPool.setMaxRecycledViews(0, 24)
            setPadding(Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 4), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 20))
            setBackgroundColor(Ui.bg(this@MainActivity))
        }
        adapter = AlbumRecyclerAdapter(this, object : AlbumRecyclerAdapter.Callbacks {
            override fun onAlbumClick(position: Int) {
                if (position !in 0 until adapter.getCount()) return
                if (adapter.isSelectionMode()) {
                    adapter.toggleSelection(position)
                    Haptics.perform(this@MainActivity, Haptics.Effect.TICK)
                    updateSelectionUi()
                    return
                }
                val album = adapter.getItem(position)
                val exposedAlbumKeys = adapter.allAlbumsSnapshot()
                    .asSequence()
                    .map { it.key }
                    .filterNot(VirtualAlbumRules::isVirtual)
                    .toCollection(ArrayList())
                MediaScanScheduler.cancelMaintenance(applicationContext)
                val intent = Intent(this@MainActivity, AlbumMediaActivity::class.java).apply {
                    putExtra("album_key", album.key)
                    putExtra("album_name", album.name)
                    putExtra("include_hidden_filesystem", shouldIncludeHiddenFilesystem())
                    putStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS, exposedAlbumKeys)
                }
                SecurityGate.requireFolder(this@MainActivity, album.key, album.name) { startActivity(intent) }
            }

            override fun onHideTemporarilyVisibleAlbum(albumKey: String) {
                TemporaryAlbumVisibility.hide(albumKey)
                scheduleRevealExpiry()
                loadAlbums()
            }

            override fun onAlbumLongClick(view: View, position: Int): Boolean {
                if (position !in 0 until adapter.getCount()) return true
                if (VirtualAlbumRules.isVirtual(adapter.getItem(position).key)) return true
                enterSelectionMode()
                adapter.selectPosition(position)
                updateSelectionUi()
                Ui.longPressFeedback(view)
                return true
            }
        })
        adapter.setSpacing(albumSpacingDp)
        adapter.setCoverSize(albumCoverSizePx())
        grid.adapter = adapter
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (newState != RecyclerView.SCROLL_STATE_IDLE) return
                val pending = pendingAlbumSubmission ?: return
                pendingAlbumSubmission = null
                submitAlbumsNow(pending.albums, pending.query)
            }
        })
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val now = System.currentTimeMillis()
                if (now - lastColumnGestureMs < 180L) return false
                if (detector.scaleFactor < 0.92f) {
                    lastColumnGestureMs = now
                    setColumnCount(columnCount + 1)
                    return true
                }
                if (detector.scaleFactor > 1.08f) {
                    lastColumnGestureMs = now
                    setColumnCount(columnCount - 1)
                    return true
                }
                return false
            }
        })
        grid.setOnTouchListener { _, event: MotionEvent ->
            scaleDetector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gridTouchDownX = event.x
                    gridTouchDownY = event.y
                    gridTouchClickCandidate = true
                }
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_CANCEL -> gridTouchClickCandidate = false
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - gridTouchDownX) > gridTouchSlop ||
                        abs(event.y - gridTouchDownY) > gridTouchSlop
                    ) {
                        gridTouchClickCandidate = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (gridTouchClickCandidate && event.pointerCount == 1) grid.performClick()
                    gridTouchClickCandidate = false
                }
            }
            false
        }
        swipeRefresh = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(Ui.accent(this@MainActivity))
            setProgressBackgroundColorSchemeColor(Ui.surface(this@MainActivity))
            isEnabled = GalleryPreferences.flag(this@MainActivity, GalleryPreferences.PULL_TO_REFRESH)
            setOnRefreshListener {
                refreshCatalogWithWorker(shouldIncludeHiddenFilesystem())
            }
        }
        swipeRefresh.addView(grid, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(swipeRefresh, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        emptyView = Ui.label(this, getString(R.string.main_empty_folders)).apply {
            visibility = View.GONE
        }
        content.addView(
            emptyView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        catalogLoadingView = LoadingIndicatorView(this, description = getString(R.string.main_loading_media)).apply { visibility = View.GONE }
        content.addView(catalogLoadingView, FrameLayout.LayoutParams(Ui.dp(this, 32), Ui.dp(this, 32), Gravity.CENTER))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        browseBar = GalleryBrowseBar(this, systemBars.bottom)
        refreshBrowseBar()
        root.addView(browseBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        selectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.surface(this@MainActivity))
            setPadding(0, Ui.dp(this@MainActivity, 1), 0, systemBars.bottom)
        }
        systemBars.onChange {
            (top.layoutParams as? ViewGroup.MarginLayoutParams)?.let { params ->
                params.topMargin = systemBars.top + Ui.dp(this, 14)
                top.layoutParams = params
            }
            browseBar.setBottomInset(systemBars.bottom)
            selectionActions.setPadding(0, Ui.dp(this, 1), 0, systemBars.bottom)
        }
        selectionActionDock = Ui.selectionActionDock(this)
        selectionActions.addView(selectionActionDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSelectionAction(R.drawable.ic_share, getString(R.string.action_share)) { shareSelectedAlbums() }
        addSelectionAction(R.drawable.ic_pin, getString(R.string.action_pin_album)) { togglePinnedSelectedAlbums() }
        addSelectionAction(R.drawable.ic_lock, getString(R.string.security_folder_protect)) { toggleSecureSelectedAlbums() }
        addSelectionAction(R.drawable.ic_trash, getString(R.string.action_delete)) { SecurityGate.requireFileOperation(this) { confirmDeleteSelectedAlbums() } }
        addSelectionAction(R.drawable.ic_arrow_right, getString(R.string.action_move)) { SecurityGate.requireFileOperation(this) { askMoveSelectedAlbums() } }
        selectionActions.visibility = View.GONE
        root.addView(selectionActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun addSelectionAction(icon: Int, label: String, listener: () -> Unit) {
        Ui.addSelectionActionToDock(selectionActionDock, Ui.selectionAction(this, icon, label, listener))
    }

    private fun iconButton(icon: Int): ImageButton =
        ImageButton(this).apply {
            setImageResource(icon)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Ui.accent(this@MainActivity))
            scaleType = ImageView.ScaleType.CENTER
            setPadding(Ui.dp(this@MainActivity, 9), Ui.dp(this@MainActivity, 9), Ui.dp(this@MainActivity, 9), Ui.dp(this@MainActivity, 9))
        }

    private fun enterSelectionMode() {
        adapter.setSelectionMode(true)
    }

    internal fun exitSelectionMode() {
        adapter.clearSelection()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val active = adapter.isSelectionMode()
        selectionBar.visibility = View.GONE
        selectionActions.visibility = if (active) View.VISIBLE else View.GONE
        browseBar.visibility = if (active) View.GONE else View.VISIBLE
        selectAllChip.visibility = if (active) View.VISIBLE else View.GONE
        searchIconButton.visibility = if (active) View.GONE else View.VISIBLE
        Ui.styleSelectionToggle(selectAllChip, adapter.allVisibleSelected())
        searchInput.hint = if (active) {
            getString(R.string.album_selected_of_total, adapter.selectedCount(), adapter.getCount())
        } else {
            getString(R.string.main_search_folders)
        }
        Ui.styleSelectionCounter(searchInput, active, idleTextSizeSp = 14f)
        searchInput.isEnabled = !active
        moreButton.setImageResource(if (active) R.drawable.ic_close else R.drawable.ic_more_vertical)
        moreButton.contentDescription = getString(
            if (active) R.string.action_cancel_selection else R.string.action_more_options
        )
        if (active && adapter.selectedCount() == 0) {
            exitSelectionMode()
        }
    }

    private fun toggleSelectAll() {
        if (adapter.allVisibleSelected()) {
            adapter.clearSelection()
        } else {
            adapter.selectAllVisible()
        }
        updateSelectionUi()
    }

    internal fun mediaForAlbums(albums: List<AlbumItem>): List<MediaItem> {
        val keys = HashSet<String>()
        var allMedia = false
        for (album in albums) {
            if (album.key == "all_media") {
                allMedia = true
            }
            keys.add(album.key)
        }
        val items = ArrayList<MediaItem>()
        val source = MediaStoreRepository.loadMedia(applicationContext, shouldIncludeHiddenFilesystem())
        val hiddenKeys = prefs.getStringSet(GalleryPreferences.HIDDEN_FOLDER_KEYS, emptySet()).orEmpty() +
            SecurityStore(prefs).secureFolderKeys() +
            AutomaticHiddenAlbums.keysForMedia(applicationContext, source,
                HiddenDirectoryMarkers(Environment.getExternalStorageDirectory()))
        for (item in source) {
            if ((keys.contains(item.albumKey) || (allMedia && !VirtualAlbumRules.isHiddenMedia(item, hiddenKeys))) &&
                matchesMediaFilter(item)) {
                items.add(item)
            }
        }
        return items
    }

    internal fun loadAlbums() {
        if (::adapter.isInitialized) adapter.secureKeys = SecurityStore(prefs).secureFolderKeys()
        val query = if (::searchInput.isInitialized) searchInput.text.toString() else ""
        if (::adapter.isInitialized && adapter.getCount() == 0 && ::emptyView.isInitialized) {
            emptyView.visibility = View.GONE
            catalogLoadingView.visibility = View.VISIBLE
        }
        catalogController.load(
            AlbumCatalogOptions(
                includeHidden = shouldIncludeHiddenFilesystem(),
                searchAllFiles = prefs.getBoolean(GalleryPreferences.SEARCH_ALL_FILES, false),
                hiddenKeys = HashSet(prefs.getStringSet(GalleryPreferences.HIDDEN_FOLDER_KEYS, HashSet()) ?: HashSet()),
                query = query,
                filterOptions = MediaFilterOptions(
                    showImages,
                    showVideos,
                    showGifs,
                    showRaw,
                    showSvgs,
                    showPortraits
                ),
                sortMode = sortMode,
                sortDescending = sortDesc,
                temporarilyVisibleKeys = TemporaryAlbumVisibility.activeKeys(),
                showNaturallyHidden = showHiddenFolders || GalleryPreferences.hiddenFlag(this, "always_show_hidden")
            ),
            onAlbums = { albums, currentQuery ->
                if (!isFinishing) showAlbumsProgressively(albums, currentQuery)
            },
            onDeferredRefreshRequired = ::scheduleDeferredCatalogRefresh,
            onFailure = {
                if (!isFinishing && !isDestroyed) {
                    if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
                    deferredCatalogRefreshPending = false
                    catalogLoadingView.visibility = View.GONE
                    if (adapter.getCount() == 0) {
                        emptyView.setText(R.string.album_load_failed)
                        emptyView.visibility = View.VISIBLE
                    } else Ui.toast(this, getString(R.string.album_load_failed))
                }
            }
        )
    }

    private fun scheduleDeferredCatalogRefresh(includeHidden: Boolean) {
        if (deferredCatalogRefreshPending || isFinishing || !::grid.isInitialized) return
        deferredCatalogRefreshPending = true
        grid.postDelayed({
            if (isFinishing) {
                deferredCatalogRefreshPending = false
            } else if (!hasWindowFocus()) {
                deferredCatalogRefreshPending = false
                mediaObserverRefreshPending = true
            } else {
                refreshCatalogWithWorker(includeHidden, force = false)
            }
        }, DEFERRED_REFRESH_DELAY_MS)
    }

    private fun showAlbumsProgressively(albums: List<AlbumItem>, query: String) {
        if (albums.size <= 60 || !::grid.isInitialized) {
            submitAlbumsWhenIdle(albums, query)
            return
        }
        submitAlbumsWhenIdle(ArrayList(albums.subList(0, 60)), query)
        grid.postDelayed({
            submitAlbumsWhenIdle(albums, query)
        }, 120)
    }

    private fun submitAlbumsWhenIdle(albums: List<AlbumItem>, query: String) {
        if (::grid.isInitialized && grid.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            pendingAlbumSubmission = PendingAlbumSubmission(ArrayList(albums), query)
            return
        }
        submitAlbumsNow(albums, query)
    }

    private fun submitAlbumsNow(albums: List<AlbumItem>, query: String) {
        observeRevealedFolders()
        rememberVisibleFolderKeys(albums)
        adapter.setPinnedKeys(catalogController.pinnedKeys)
        adapter.setTemporarilyVisibleKeys(TemporaryAlbumVisibility.activeKeys())
        adapter.submit(albums, query)
        AlbumSidebarSource.albums = adapter.allAlbumsSnapshot()
        mergeVisibleAlbumsIntoDialog?.invoke(albums) ?: refreshVisibilityDialogCounts?.invoke()
        if (forceAlbumCoverRefreshOnNextSubmit) {
            forceAlbumCoverRefreshOnNextSubmit = false
            adapter.refreshVisibleCovers()
        }
        updateEmptyText()
        if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
    }

    internal fun rememberVisibleFolderKeys(albums: Collection<AlbumItem>) {
        val previous = prefs.getStringSet(PREF_EVER_VISIBLE_FOLDER_KEYS, HashSet()) ?: HashSet()
        val temporaryKeys = TemporaryAlbumVisibility.activeKeys()
        val updated = HiddenAlbumDialogRules.rememberVisible(
            previous, albums.map { it.key }.filterNot { it in temporaryKeys }
        )
        if (updated != previous) {
            prefs.edit().putStringSet(PREF_EVER_VISIBLE_FOLDER_KEYS, HashSet(updated)).apply()
        }
    }

    private fun loadSettings() {
        sortMode = prefs.getString(GalleryPreferences.SORT_MODE, SORT_MODIFIED) ?: SORT_MODIFIED
        sortDesc = prefs.getBoolean(GalleryPreferences.SORT_DESC, true)
        showImages = prefs.getBoolean(GalleryPreferences.FILTER_IMAGES, true)
        showVideos = prefs.getBoolean(GalleryPreferences.FILTER_VIDEOS, true)
        showGifs = prefs.getBoolean(GalleryPreferences.FILTER_GIFS, true)
        showRaw = prefs.getBoolean(GalleryPreferences.FILTER_RAW, true)
        showSvgs = prefs.getBoolean(GalleryPreferences.FILTER_SVGS, true)
        showPortraits = prefs.getBoolean(GalleryPreferences.FILTER_PORTRAITS, false)
        showHiddenFolders = GalleryPreferences.hiddenFlag(this, GalleryPreferences.SHOW_HIDDEN_FOLDERS) ||
            GalleryPreferences.hiddenFlag(this, "always_show_hidden")
        columnCount = prefs.getInt(GalleryPreferences.COLUMN_COUNT, 3)
        if (columnCount < 2 || columnCount > 6) {
            columnCount = 3
        }
        albumSpacingDp = prefs.getInt(GalleryPreferences.ALBUM_SPACING_DP, 8).coerceIn(0, 16)
        if (::adapter.isInitialized) {
            adapter.setSpacing(albumSpacingDp)
            adapter.setCoverSize(albumCoverSizePx())
        }
    }

    private fun applyThemeColors() {
        Ui.applySystemBars(this)
        if (::root.isInitialized) root.setBackgroundColor(Ui.bg(this))
        if (::top.isInitialized) top.background = Ui.rounded(Ui.search(this), 22, this, Ui.border(this), 1)
        if (::searchInput.isInitialized) {
            searchInput.setTextColor(Ui.text(this))
            searchInput.setHintTextColor(Ui.muted(this))
        }
        if (::selectAllChip.isInitialized) {
            selectAllChip.setTextColor(Ui.text(this))
        }
        if (::searchIconButton.isInitialized) {
            searchIconButton.setColorFilter(Ui.accent(this))
        }
        if (::moreButton.isInitialized) {
            moreButton.setColorFilter(Ui.accent(this))
        }
        if (::grid.isInitialized) grid.setBackgroundColor(Ui.bg(this))
        if (::swipeRefresh.isInitialized) {
            swipeRefresh.setColorSchemeColors(Ui.accent(this))
            swipeRefresh.setProgressBackgroundColorSchemeColor(Ui.surface(this))
        }
        if (::selectionBar.isInitialized) selectionBar.background = Ui.rounded(Ui.elevatedSurface(this), 10, this, Ui.border(this), 1)
        if (::browseBar.isInitialized) browseBar.restyle()
        if (::selectionActions.isInitialized) {
            selectionActions.setBackgroundColor(Ui.surface(this))
            Ui.restyleSelectionActionDock(selectionActionDock)
        }
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    internal fun setColumnCount(nextCount: Int) {
        val bounded = max(2, min(6, nextCount))
        if (bounded == columnCount) return
        columnCount = bounded
        prefs.edit().putInt(GalleryPreferences.COLUMN_COUNT, columnCount).apply()
        if (::grid.isInitialized) {
            grid.animate()
                .alpha(0.82f)
                .scaleX(0.985f)
                .scaleY(0.985f)
                .setDuration(85)
                .withEndAction {
                    layoutManager.spanCount = columnCount
                    adapter.setCoverSize(albumCoverSizePx())
                    grid.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(130).start()
                }
                .start()
        }
    }

    internal fun albumCoverSizePx(): Int {
        val horizontalPadding = Ui.dp(this, 12)
        val itemPadding = Ui.dp(this, albumSpacingDp) * 2 * columnCount
        return max(Ui.dp(this, 96), (resources.displayMetrics.widthPixels - horizontalPadding - itemPadding) / columnCount)
    }

    internal fun sortAlbums(albums: MutableList<AlbumItem>) {
        AlbumRules.sort(albums, sortMode, sortDesc)
    }

    internal fun isHiddenAlbum(album: AlbumItem): Boolean =
        album.naturallyHidden || AlbumRules.isHidden(album.path, album.key) || album.key in automaticallyHiddenKeys

    private fun matchesMediaFilter(item: MediaItem): Boolean = MediaFilterRules.matches(
        item.name,
        item.mimeType,
        MediaFilterOptions(showImages, showVideos, showGifs, showRaw, showSvgs, showPortraits)
    )

    private fun registerMediaObserver() {
        try {
            contentResolver.registerContentObserver(MediaStore.Files.getContentUri("external"), true, mediaObserver)
        } catch (_: Exception) {
        }
    }

    private fun refreshCatalogWithWorker(includeHidden: Boolean, force: Boolean = true) {
        if (force && ::adapter.isInitialized) adapter.refreshVisibleCovers()
        catalogController.refreshCatalog(
            owner = this,
            includeHidden = includeHidden,
            force = force,
            onSuccess = {
                deferredCatalogRefreshPending = false
                forceAlbumCoverRefreshOnNextSubmit = force
                if (!isFinishing) loadAlbums()
                if (mainScreenResumed && hasWindowFocus() && mediaObserverRefreshPending) scheduleMediaRefresh()
            },
            onFailure = {
                deferredCatalogRefreshPending = false
                if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
            }
        )
    }

    private fun scheduleMediaRefresh() {
        mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
        mediaObserverRefreshScheduled = true
        mediaRefreshHandler.postDelayed(mediaRefreshRunnable, 700L)
    }

    private fun updateEmptyText() {
        if (!::adapter.isInitialized || !::emptyView.isInitialized) return
        catalogLoadingView.visibility = View.GONE
        emptyView.setText(R.string.main_empty_folders)
        emptyView.visibility = if (adapter.getCount() == 0) View.VISIBLE else View.GONE
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        accessCoordinator.onRequestPermissionsResult(
            requestCode,
            onGranted = ::loadAlbums,
            onDenied = {
                catalogLoadingView.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
                emptyView.setText(R.string.access_authorize_media)
            }
        )
    }

    internal fun shouldIncludeHiddenFilesystem(): Boolean =
        accessCoordinator.includeHiddenFilesystem(
            showHiddenFolders || GalleryPreferences.hiddenFlag(this, "always_show_hidden") ||
                TemporaryAlbumVisibility.requiresHiddenFilesystem()
        )

    internal fun scheduleRevealExpiry() {
        mediaRefreshHandler.removeCallbacks(revealExpiryRunnable)
        if (!mainScreenResumed) return
        TemporaryAlbumVisibility.nextExpiryDelay()?.let {
            mediaRefreshHandler.postDelayed(revealExpiryRunnable, it)
        }
    }

    private fun observeRevealedFolders() {
        val wanted = if (MediaActions.hasAllFilesAccess(this)) TemporaryAlbumVisibility.activeKeys() else emptySet()
        val removed = revealedFolderObservers.keys.filterNot { it in wanted }
        removed.forEach { revealedFolderObservers.remove(it)?.stopWatching() }
        val root = Environment.getExternalStorageDirectory()
        for (key in wanted) {
            if (key in revealedFolderObservers || !MediaStoreRepository.isPhysicalAlbum(key)) continue
            val directory = java.io.File(root, key)
            if (!runCatching { directory.canonicalPath.startsWith(root.canonicalPath + java.io.File.separator) }.getOrDefault(false)) continue
            val observer = object : android.os.FileObserver(directory.absolutePath,
                CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE or ATTRIB or DELETE_SELF or MOVE_SELF) {
                override fun onEvent(event: Int, path: String?) {
                    mediaRefreshHandler.post {
                        if (!isFinishing && !isDestroyed) mediaObserver.onChange(false, null)
                    }
                }
            }
            observer.startWatching()
            revealedFolderObservers[key] = observer
        }
    }

    companion object {
        const val EXTRA_HIDDEN_PANEL = "gallery_hidden_panel"
        internal const val REQ_BATCH_DELETE = 10
        private const val PREFS = "gallery_albums"
        internal const val PREF_PINNED_HIDDEN_FOLDER_KEYS = "pinned_hidden_folder_keys"
        internal const val PREF_EVER_VISIBLE_FOLDER_KEYS = "ever_visible_folder_keys"
        private const val DEFERRED_REFRESH_DELAY_MS = 900L
        private const val INITIAL_CATALOG_DELAY_MS = 90L
        internal const val SORT_NAME = AlbumRules.SORT_NAME
        internal const val SORT_PATH = AlbumRules.SORT_PATH
        internal const val SORT_SIZE = AlbumRules.SORT_SIZE
        internal const val SORT_MODIFIED = AlbumRules.SORT_MODIFIED
        internal const val SORT_CREATED = AlbumRules.SORT_CREATED
        internal const val SORT_RANDOM = AlbumRules.SORT_RANDOM
    }

    private data class PendingAlbumSubmission(
        val albums: List<AlbumItem>,
        val query: String
    )
}
