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
import android.widget.ListView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private lateinit var adapter: AlbumRecyclerAdapter
    private lateinit var emptyView: TextView
    private lateinit var catalogLoadingView: LoadingIndicatorView
    private lateinit var searchInput: EditText
    private lateinit var grid: AccessibleRecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var root: LinearLayout
    private lateinit var top: LinearLayout
    private lateinit var searchIconButton: ImageButton
    private lateinit var selectAllChip: TextView
    private lateinit var moreButton: ImageButton
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionActions: LinearLayout
    private lateinit var selectionActionDock: LinearLayout
    private lateinit var selectAllText: TextView
    private lateinit var prefs: SharedPreferences
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var accessCoordinator: MainMediaAccessCoordinator
    private lateinit var catalogController: AlbumCatalogController
    private var sortMode = SORT_MODIFIED
    private var sortDesc = true
    private var showImages = true
    private var showVideos = true
    private var showGifs = true
    private var showRaw = true
    private var showSvgs = true
    private var showPortraits = false
    private var showHiddenFolders = false
    private var automaticallyHiddenKeys: Set<String> = emptySet()
    private var columnCount = 3
    private var lastColumnGestureMs = 0L
    private var gridTouchDownX = 0f
    private var gridTouchDownY = 0f
    private var gridTouchClickCandidate = false
    private val gridTouchSlop by lazy { ViewConfiguration.get(this).scaledTouchSlop.toFloat() }
    private val mediaLoader = Executors.newSingleThreadExecutor()
    private var firstResume = true
    private var mainScreenResumed = false
    private var mediaObserverRefreshPending = false
    private var mediaObserverRefreshScheduled = false
    private var deferredCatalogRefreshPending = false
    private var pendingAlbumSubmission: PendingAlbumSubmission? = null
    private var forceAlbumCoverRefreshOnNextSubmit = false
    private var visibilityDialogAdapter: BaseAdapter? = null
    private var refreshVisibilityDialogCounts: (() -> Unit)? = null
    private var mergeVisibleAlbumsIntoDialog: ((List<AlbumItem>) -> Unit)? = null
    private val revealedFolderObservers = HashMap<String, android.os.FileObserver>()
    private val visibilityCountRefreshRunnable = Runnable { refreshVisibilityDialogCounts?.invoke() }
    private val mediaRefreshHandler = Handler(Looper.getMainLooper())
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
        if (savedInstanceState == null) TemporaryAlbumVisibility.clear()
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        Ui.applySystemBars(this)
        accessCoordinator = MainMediaAccessCoordinator(this, prefs)
        catalogController = AlbumCatalogController(applicationContext)
        loadSettings()
        buildLayout()
        registerMediaObserver()
        accessCoordinator.start(::scheduleInitialCatalogLoad)
    }

    override fun onResume() {
        super.onResume()
        mainScreenResumed = true
        scheduleRevealExpiry()
        if (firstResume) {
            firstResume = false
            return
        }
        loadSettings()
        applyThemeColors()
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
        if (!isChangingConfigurations) TemporaryAlbumVisibility.clear()
        revealedFolderObservers.values.forEach { it.stopWatching() }
        revealedFolderObservers.clear()
        try {
            contentResolver.unregisterContentObserver(mediaObserver)
        } catch (_: Exception) {
        }
        catalogController.close()
        mediaLoader.shutdownNow()
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
            setMargins(Ui.dp(this@MainActivity, 24), statusBarHeight() + Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 24), Ui.dp(this@MainActivity, 14))
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
                    showMenu(it)
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
                startActivity(intent)
            }

            override fun onAlbumLongClick(view: View, position: Int): Boolean {
                if (position !in 0 until adapter.getCount()) return true
                if (VirtualAlbumRules.isVirtual(adapter.getItem(position).key)) return true
                enterSelectionMode()
                adapter.selectPosition(position)
                updateSelectionUi()
                view.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.78f).setDuration(90).start()
                return true
            }
        })
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

        selectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.surface(this@MainActivity))
            setPadding(0, Ui.dp(this@MainActivity, 1), 0, navigationBarHeight())
        }
        selectionActionDock = Ui.selectionActionDock(this)
        selectionActions.addView(selectionActionDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSelectionAction(R.drawable.ic_share, getString(R.string.action_share)) { shareSelectedAlbums() }
        addSelectionAction(R.drawable.ic_pin, getString(R.string.action_pin_album)) { togglePinnedSelectedAlbums() }
        addSelectionAction(R.drawable.ic_trash, getString(R.string.action_delete)) { confirmDeleteSelectedAlbums() }
        addSelectionAction(R.drawable.ic_arrow_right, getString(R.string.action_move)) { askMoveSelectedAlbums() }
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

    private fun showMenu(anchor: View) {
        val sort = getString(R.string.action_sort_by)
        val filter = getString(R.string.action_filter_media)
        val organization = getString(R.string.main_folder_organization)
        val visibility = getString(R.string.main_folder_visibility)
        val createFolder = getString(R.string.action_create_folder)
        val settings = getString(R.string.action_settings)
        val refresh = getString(R.string.action_refresh)
        val showHiddenTrash = prefs.getBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, false)
        val trashVisibility = getString(
            if (showHiddenTrash) R.string.trash_hide_hidden else R.string.trash_show_hidden
        )
        Ui.showPopupOptions(
            anchor,
            listOf(sort, filter, organization, visibility, createFolder, trashVisibility, settings, refresh)
        ) { selected ->
            when (selected) {
                sort -> showSortDialog()
                filter -> showMediaFilterDialog()
                organization -> showFolderOrganizationDialog()
                visibility -> showFolderVisibilityDialog()
                createFolder -> FolderCreationMenu(this) { loadAlbums() }.show()
                trashVisibility -> {
                    prefs.edit().putBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, !showHiddenTrash).apply()
                    loadAlbums()
                }
                settings -> startActivity(Intent(this, SettingsActivity::class.java))
                else -> loadAlbums()
            }
        }
    }

    private fun enterSelectionMode() {
        adapter.setSelectionMode(true)
    }

    private fun exitSelectionMode() {
        adapter.clearSelection()
        updateSelectionUi()
    }

    private fun updateSelectionUi() {
        val active = adapter.isSelectionMode()
        selectionBar.visibility = View.GONE
        selectionActions.visibility = if (active) View.VISIBLE else View.GONE
        selectAllChip.visibility = if (active) View.VISIBLE else View.GONE
        searchIconButton.visibility = if (active) View.GONE else View.VISIBLE
        Ui.styleSelectionToggle(selectAllChip, adapter.allVisibleSelected())
        searchInput.hint = if (active) {
            resources.getQuantityString(R.plurals.selected_count, adapter.selectedCount(), adapter.selectedCount())
        } else {
            getString(R.string.main_search_folders)
        }
        searchInput.isEnabled = !active
        moreButton.setImageResource(if (active) R.drawable.ic_back else R.drawable.ic_more_vertical)
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

    private fun shareSelectedAlbums() {
        val albums = adapter.selectedAlbums()
        if (albums.isEmpty()) return
        mediaLoader.execute {
            val uris = ArrayList<Uri>()
            for (item in mediaForAlbums(albums)) {
                uris.add(item.uri)
            }
            runOnUiThread {
                if (uris.isEmpty()) {
                    Ui.toast(this, getString(R.string.main_no_media_to_share))
                    return@runOnUiThread
                }
                val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, getString(R.string.action_share)))
            }
        }
    }

    private fun togglePinnedSelectedAlbums() {
        val albums = adapter.selectedAlbums()
        if (albums.isEmpty()) return
        val pinned = HashSet(prefs.getStringSet(VirtualAlbumRules.PINNED_ALBUMS_PREF, emptySet()).orEmpty())
        val unpin = albums.all { it.key in pinned }
        if (unpin) pinned.removeAll(albums.map { it.key }) else pinned.addAll(albums.map { it.key })
        prefs.edit().putStringSet(VirtualAlbumRules.PINNED_ALBUMS_PREF, pinned).apply()
        exitSelectionMode()
        loadAlbums()
    }

    private fun confirmDeleteSelectedAlbums() {
        val albums = adapter.selectedAlbums()
        if (albums.isEmpty()) return
        val visibleCount = albums.sumOf { it.count }
        val permanent = !TrashPreferences.isEnabled(this)
        Ui.showConfirmationDialog(
            this,
            getString(R.string.main_delete_albums_title),
            getString(
                if (permanent) R.string.main_delete_albums_permanently_message else R.string.main_delete_albums_message,
                resources.getQuantityString(R.plurals.albums_count, albums.size, albums.size),
                resources.getQuantityString(
                    R.plurals.approximately_items_removed,
                    visibleCount,
                    visibleCount
                )
            ),
            getString(if (permanent) R.string.action_delete_permanently else R.string.action_move_to_trash)
        ) { deleteSelectedAlbums(albums) }
    }

    private fun deleteSelectedAlbums(albums: List<AlbumItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            accessCoordinator.ensureFullAccess(true)
            return
        }
        mediaLoader.execute {
            val completed = arrayListOf<MediaItem>()
            for (item in mediaForAlbums(albums)) {
                if (MediaActions.requestDelete(this, item.uri, REQ_BATCH_DELETE) == MediaActions.RESULT_DONE) {
                    completed.add(item)
                }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                val deleted = completed.size
                adapter.removeCompletedItems(completed, moved = false)
                Ui.toast(this, resources.getQuantityString(
                    if (TrashPreferences.isEnabled(this)) R.plurals.items_moved_to_trash else R.plurals.items_deleted,
                    deleted, deleted))
                exitSelectionMode()
                loadAlbums()
            }
        }
    }

    private fun askMoveSelectedAlbums() {
        val albums = adapter.selectedAlbums()
        if (albums.isEmpty()) return
        val excludedKeys = albums.mapTo(HashSet()) { it.key }
        val exposedAlbums = adapter.allAlbumsSnapshot()
        mediaLoader.execute {
            val targets = AlbumTargetRules.exposedTargets(
                exposedAlbums,
                exposedAlbums.mapTo(HashSet()) { it.key },
                emptySet(),
                excludedKeys
            )
            runOnUiThread {
                if (isFinishing || !moreButton.isAttachedToWindow) return@runOnUiThread
                if (targets.isEmpty()) {
                    Ui.toast(this, getString(R.string.main_no_move_target))
                    return@runOnUiThread
                }
                Ui.showAlbumTargets(moreButton, getString(R.string.action_move_to), targets) { album ->
                    moveSelectedAlbums(albums, album)
                }
            }
        }
    }

    private fun moveSelectedAlbums(albums: List<AlbumItem>, destination: AlbumItem) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            accessCoordinator.ensureFullAccess(true)
            return
        }
        mediaLoader.execute {
            val actions = AlbumSelectionActions(this, prefs)
            val result = try {
                actions.move(mediaForAlbums(albums), destination.path.ifBlank { destination.name })
            } finally {
                actions.close()
            }
            val moved = result.completed
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.removeCompletedItems(result.completedItems, moved = true)
                Ui.toast(this, resources.getQuantityString(R.plurals.items_moved, moved, moved))
                exitSelectionMode()
                if (albums.any { it.key in result.emptiedAlbumKeys }) {
                    startActivity(MediaOperationNavigation.destinationIntent(this, intent, destination.key, destination.name))
                }
                if (moved > 0) {
                    if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = true
                    loadAlbums()
                } else {
                    loadAlbums()
                }
            }
        }
    }

    private fun mediaForAlbums(albums: List<AlbumItem>): List<MediaItem> {
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
        val hiddenKeys = prefs.getStringSet("hidden_folder_keys", emptySet()).orEmpty() +
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

    private fun loadAlbums() {
        val query = if (::searchInput.isInitialized) searchInput.text.toString() else ""
        if (::adapter.isInitialized && adapter.getCount() == 0 && ::emptyView.isInitialized) {
            emptyView.visibility = View.GONE
            catalogLoadingView.visibility = View.VISIBLE
        }
        catalogController.load(
            AlbumCatalogOptions(
                includeHidden = shouldIncludeHiddenFilesystem(),
                searchAllFiles = prefs.getBoolean("search_all_files", false),
                hiddenKeys = HashSet(prefs.getStringSet("hidden_folder_keys", HashSet()) ?: HashSet()),
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
                showNaturallyHidden = showHiddenFolders || prefs.getBoolean("always_show_hidden", false)
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
        adapter.setPinnedKeys(prefs.getStringSet(VirtualAlbumRules.PINNED_ALBUMS_PREF, emptySet()).orEmpty())
        adapter.setTemporarilyVisibleKeys(TemporaryAlbumVisibility.activeKeys())
        adapter.submit(albums, query)
        mergeVisibleAlbumsIntoDialog?.invoke(albums) ?: refreshVisibilityDialogCounts?.invoke()
        if (forceAlbumCoverRefreshOnNextSubmit) {
            forceAlbumCoverRefreshOnNextSubmit = false
            adapter.refreshVisibleCovers()
        }
        updateEmptyText()
        if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
    }

    private fun rememberVisibleFolderKeys(albums: Collection<AlbumItem>) {
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
        sortMode = prefs.getString("sort_mode", SORT_MODIFIED) ?: SORT_MODIFIED
        sortDesc = prefs.getBoolean("sort_desc", true)
        showImages = prefs.getBoolean("filter_images", true)
        showVideos = prefs.getBoolean("filter_videos", true)
        showGifs = prefs.getBoolean("filter_gifs", true)
        showRaw = prefs.getBoolean("filter_raw", true)
        showSvgs = prefs.getBoolean("filter_svgs", true)
        showPortraits = prefs.getBoolean("filter_portraits", false)
        showHiddenFolders = prefs.getBoolean("show_hidden_folders", false) ||
            prefs.getBoolean("always_show_hidden", false)
        columnCount = prefs.getInt("column_count", 3)
        if (columnCount < 2 || columnCount > 6) {
            columnCount = 3
        }
    }

    private fun applyThemeColors() {
        Ui.applySystemBars(this)
        if (::root.isInitialized) root.setBackgroundColor(Ui.bg(this))
        if (::top.isInitialized) top.background = Ui.rounded(Ui.search(this), 22, this)
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
        if (::selectionBar.isInitialized) selectionBar.background = Ui.rounded(Ui.surface(this), 8, this)
        if (::selectionActions.isInitialized) {
            selectionActions.setBackgroundColor(Ui.surface(this))
            Ui.restyleSelectionActionDock(selectionActionDock)
        }
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun showSortDialog() {
        val labels = resources.getStringArray(R.array.main_sort_labels)
        val modes = arrayOf(SORT_NAME, SORT_PATH, SORT_SIZE, SORT_MODIFIED, SORT_CREATED, SORT_RANDOM)
        val checked = modes.indexOf(sortMode).takeIf { it >= 0 } ?: 3

        Ui.showSortChoiceDialog(
            this,
            getString(R.string.action_sort_by),
            labels,
            modes,
            checked,
            sortDesc
        ) { which, descending ->
            sortMode = modes[which]
            sortDesc = descending
            saveSorting()
            loadAlbums()
        }
    }

    private fun saveSorting() {
        prefs.edit()
            .putString("sort_mode", sortMode)
            .putBoolean("sort_desc", sortDesc)
            .apply()
    }

    private fun showMediaFilterDialog() {
        val labels = resources.getStringArray(R.array.main_media_filter_labels)
        val checked = booleanArrayOf(showImages, showVideos, showGifs, showRaw, showSvgs, showPortraits)
        Ui.showMultiChoiceDialog(this, getString(R.string.action_filter_media), labels, checked) { selected ->
            showImages = selected[0]
            showVideos = selected[1]
            showGifs = selected[2]
            showRaw = selected[3]
            showSvgs = selected[4]
            showPortraits = selected[5]
            prefs.edit()
                .putBoolean("filter_images", showImages)
                .putBoolean("filter_videos", showVideos)
                .putBoolean("filter_gifs", showGifs)
                .putBoolean("filter_raw", showRaw)
                .putBoolean("filter_svgs", showSvgs)
                .putBoolean("filter_portraits", showPortraits)
                .apply()
            loadAlbums()
        }
    }

    private fun showFolderOrganizationDialog() {
        val labels = resources.getStringArray(R.array.folder_column_labels)
        val checked = max(0, min(4, columnCount - 2))
        Ui.showChoiceDialog(
            this,
            getString(R.string.main_folder_organization),
            labels,
            checked,
            message = getString(R.string.main_folder_organization_hint)
        ) { which ->
            setColumnCount(which + 2)
        }
    }

    private fun showFolderVisibilityDialog() {
        val currentAlbums = adapter.visibleAlbumsSnapshot().filter { it.key != "all_media" }
        rememberVisibleFolderKeys(currentAlbums)
        // Open before any database/filesystem recount. The updater belongs to this
        // dialog and ignores deliveries after dismissal or replacement.
        val updateRemembered = showFolderVisibilityDialog(currentAlbums)
        val previouslyVisibleKeys = HashSet(
            prefs.getStringSet(PREF_EVER_VISIBLE_FOLDER_KEYS, HashSet()) ?: HashSet()
        )
        val hiddenKeys = HashSet(prefs.getStringSet("hidden_folder_keys", HashSet()) ?: HashSet())
        mediaLoader.execute {
            val visibleCatalog = GalleryCatalogStore.readAlbums(applicationContext, false)
            val automaticKeys = AutomaticHiddenAlbums.keys(applicationContext, visibleCatalog,
                HiddenDirectoryMarkers(Environment.getExternalStorageDirectory()))
            val migratedVisibleKeys = visibleCatalog
                .filter { hiddenKeys.contains(it.key) && it.key !in automaticKeys &&
                    !AlbumRules.isHidden(it.path, it.key) }
                .map { it.key }
            val knownKeys = HashSet(previouslyVisibleKeys).apply { addAll(migratedVisibleKeys) }
            if (knownKeys != previouslyVisibleKeys) {
                prefs.edit().putStringSet(PREF_EVER_VISIBLE_FOLDER_KEYS, knownKeys).apply()
            }
            val allowedKeys = HiddenAlbumDialogRules.keysForInitialDialog(
                currentAlbums.map { it.key },
                knownKeys
            )
            val rememberedByKey = LinkedHashMap<String, AlbumItem>()
            for (album in GalleryCatalogStore.readAlbums(applicationContext, true)) {
                if (allowedKeys.contains(album.key)) rememberedByKey[album.key] = album
            }
            // Prefer refreshed metadata over snapshots retained by the screen.
            for (album in currentAlbums) {
                if (allowedKeys.contains(album.key) && !rememberedByKey.containsKey(album.key)) {
                    rememberedByKey[album.key] = album
                }
            }
            for (album in visibleCatalog) {
                if (allowedKeys.contains(album.key)) rememberedByKey[album.key] = album
            }
            val rememberedAlbums = HiddenAlbumClassification.classify(applicationContext, rememberedByKey.values.toList())
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val albumsByKey = LinkedHashMap<String, AlbumItem>()
                for (album in rememberedAlbums) albumsByKey[album.key] = album
                val albums = albumsByKey.values.toMutableList()
                sortAlbums(albums)
                updateRemembered(albums)
            }
        }
    }

    private fun showFolderVisibilityDialog(albums: List<AlbumItem>): (List<AlbumItem>) -> Unit {
        automaticallyHiddenKeys = albums.filter { it.naturallyHidden }.mapTo(HashSet()) { it.key }
        val hiddenKeys = HashSet(prefs.getStringSet("hidden_folder_keys", HashSet()) ?: HashSet())
        val pinnedKeys = HashSet(prefs.getStringSet(PREF_PINNED_HIDDEN_FOLDER_KEYS, HashSet()) ?: HashSet())
        val everVisibleKeys = HashSet(
            prefs.getStringSet(PREF_EVER_VISIBLE_FOLDER_KEYS, HashSet()) ?: HashSet()
        )
        val temporaryKeys = TemporaryAlbumVisibility.activeKeys()
        everVisibleKeys.addAll(albums.map { it.key }.filterNot {
            VirtualAlbumRules.isVirtual(it) || it in temporaryKeys
        })
        val mutableAlbums = albums.filterNot { VirtualAlbumRules.isVirtual(it.key) }.toMutableList()
        val checkedKeys = HashSet<String>()
        lateinit var dialog: AlertDialog
        val dialogBg = Ui.menuSurface(this)
        val dialogRow = Ui.blend(dialogBg, Color.WHITE, 0.04f)
        val dialogText = Ui.menuText(this)
        val dialogMuted = Ui.blend(dialogText, dialogBg, 0.34f)
        for (album in mutableAlbums) {
            if (!hiddenKeys.contains(album.key) && (showHiddenFolders || !isHiddenAlbum(album))) {
                checkedKeys.add(album.key)
            }
        }

        fun sortVisibilityAlbums() {
            sortAlbums(mutableAlbums)
            mutableAlbums.sortWith { first, second ->
                val firstPinned = pinnedKeys.contains(first.key)
                val secondPinned = pinnedKeys.contains(second.key)
                when {
                    firstPinned && !secondPinned -> -1
                    !firstPinned && secondPinned -> 1
                    else -> 0
                }
            }
        }

        fun savePinnedFolders() {
            prefs.edit()
                .putStringSet(PREF_PINNED_HIDDEN_FOLDER_KEYS, HashSet(pinnedKeys))
                .apply()
        }

        sortVisibilityAlbums()
        lateinit var listAdapter: BaseAdapter
        val listView = ListView(this).apply {
            choiceMode = ListView.CHOICE_MODE_NONE
            divider = null
            cacheColorHint = Color.TRANSPARENT
            setBackgroundColor(dialogBg)
        }
        listAdapter = object : BaseAdapter() {
            override fun getCount(): Int = mutableAlbums.size
            override fun getItem(position: Int): AlbumItem = mutableAlbums[position]
            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = (convertView as? LinearLayout) ?: LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = Ui.dp(this@MainActivity, 58)
                    setPadding(
                        Ui.dp(this@MainActivity, 18),
                        Ui.dp(this@MainActivity, 8),
                        Ui.dp(this@MainActivity, 12),
                        Ui.dp(this@MainActivity, 8)
                    )
                    addView(
                        TextView(this@MainActivity).apply {
                            tag = "label"
                            textSize = 16f
                            setTextColor(dialogText)
                            gravity = Gravity.CENTER_VERTICAL
                        },
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )
                    addView(
                        ImageButton(this@MainActivity).apply {
                            tag = "pin"
                            setImageResource(R.drawable.ic_pin)
                            setBackgroundColor(Color.TRANSPARENT)
                            scaleType = ImageView.ScaleType.CENTER
                            contentDescription = getString(R.string.main_pin_hidden)
                            setPadding(
                                Ui.dp(this@MainActivity, 8),
                                Ui.dp(this@MainActivity, 8),
                                Ui.dp(this@MainActivity, 8),
                                Ui.dp(this@MainActivity, 8)
                            )
                        },
                        LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 44), Ui.dp(this@MainActivity, 44))
                    )
                    addView(
                        ImageButton(this@MainActivity).apply {
                            tag = "eye"
                            setImageResource(R.drawable.ic_eye_off)
                            setBackgroundColor(Color.TRANSPARENT)
                            scaleType = ImageView.ScaleType.CENTER
                            contentDescription = getString(R.string.main_reveal_hidden_temporarily)
                            setPadding(
                                Ui.dp(this@MainActivity, 8),
                                Ui.dp(this@MainActivity, 8),
                                Ui.dp(this@MainActivity, 8),
                                Ui.dp(this@MainActivity, 8)
                            )
                        },
                        LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 44), Ui.dp(this@MainActivity, 44))
                    )
                    addView(
                        CheckBox(this@MainActivity).apply {
                            tag = "check"
                            buttonTintList = android.content.res.ColorStateList.valueOf(dialogText)
                            isClickable = false
                            isFocusable = false
                            gravity = Gravity.CENTER
                        },
                        LinearLayout.LayoutParams(Ui.dp(this@MainActivity, 44), Ui.dp(this@MainActivity, 44))
                    )
                }
                val album = getItem(position)
                val label = row.findViewWithTag<TextView>("label")
                val pin = row.findViewWithTag<ImageButton>("pin")
                val eye = row.findViewWithTag<ImageButton>("eye")
                val check = row.findViewWithTag<CheckBox>("check")
                val pinned = pinnedKeys.contains(album.key)
                label.text = getString(R.string.main_album_count_label, album.name, album.count)
                label.setTextColor(dialogText)
                pin.setImageResource(if (pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin)
                pin.setColorFilter(if (pinned) dialogText else dialogMuted)
                pin.alpha = if (pinned) 1f else 0.48f
                pin.contentDescription = getString(
                    if (pinned) R.string.main_unpin_hidden else R.string.main_pin_hidden
                )
                pin.setOnClickListener {
                    if (pinnedKeys.contains(album.key)) {
                        pinnedKeys.remove(album.key)
                    } else {
                        pinnedKeys.add(album.key)
                    }
                    val nowPinned = pinnedKeys.contains(album.key)
                    pin.setImageResource(if (nowPinned) R.drawable.ic_pin_filled else R.drawable.ic_pin)
                    pin.setColorFilter(if (nowPinned) dialogText else dialogMuted)
                    pin.alpha = if (nowPinned) 1f else 0.48f
                    animateHiddenControl(pin)
                    savePinnedFolders()
                    sortVisibilityAlbums()
                    pin.postDelayed({
                        notifyDataSetChanged()
                        if (nowPinned) listView.smoothScrollToPosition(0)
                    }, 190L)
                }
                val temporarilyRevealed = album.key in TemporaryAlbumVisibility.activeKeys()
                eye.visibility = if (album.key !in checkedKeys &&
                    (album.key in hiddenKeys || isHiddenAlbum(album))) View.VISIBLE else View.INVISIBLE
                eye.setImageResource(if (temporarilyRevealed) R.drawable.ic_eye else R.drawable.ic_eye_off)
                eye.setColorFilter(if (temporarilyRevealed) dialogText else dialogMuted)
                eye.alpha = if (temporarilyRevealed) 1f else 0.65f
                eye.contentDescription = getString(
                    if (temporarilyRevealed) R.string.main_hide_revealed_album
                    else R.string.main_reveal_hidden_temporarily
                )
                eye.setOnClickListener {
                    val requiresFilesystem = album.requiresFilesystem || AlbumRules.isHidden(album.path, album.key)
                    if (requiresFilesystem && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        !MediaActions.hasAllFilesAccess(this@MainActivity)) {
                        Ui.toast(this@MainActivity, getString(R.string.access_hidden_folders_required))
                        accessCoordinator.ensureFullAccess(true)
                        return@setOnClickListener
                    }
                    val revealed = TemporaryAlbumVisibility.toggle(album.key, requiresFilesystem)
                    eye.setImageResource(if (revealed) R.drawable.ic_eye else R.drawable.ic_eye_off)
                    eye.setColorFilter(if (revealed) dialogText else dialogMuted)
                    eye.alpha = if (revealed) 1f else 0.65f
                    animateHiddenControl(eye)
                    scheduleRevealExpiry()
                    mediaRefreshHandler.postDelayed({
                        if (dialog.isShowing) dialog.dismiss()
                        if (!isFinishing) loadAlbums()
                    }, 190L)
                }
                check.isChecked = checkedKeys.contains(album.key)
                row.alpha = if (check.isChecked) 1f else 0.56f
                row.setBackgroundColor(if (position % 2 == 0) dialogBg else dialogRow)
                row.setOnClickListener {
                    if (checkedKeys.contains(album.key)) {
                        checkedKeys.remove(album.key)
                    } else {
                        checkedKeys.add(album.key)
                    }
                    check.isChecked = checkedKeys.contains(album.key)
                    animateHiddenControl(check)
                    notifyDataSetChanged()
                }
                return row
            }
        }
        listView.adapter = listAdapter
        visibilityDialogAdapter = listAdapter
        val dialogListHeight = min(
            Ui.dp(this, 248),
            (resources.displayMetrics.heightPixels * 0.29f).toInt()
        )
        val refresher = SwipeRefreshLayout(this).apply {
            setColorSchemeColors(dialogText)
            setProgressBackgroundColorSchemeColor(dialogBg)
            addView(listView, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dialogListHeight))
        }

        var showHiddenCheck: CheckBox? = null
        var showHiddenLabel: TextView? = null
        var loadHiddenButton: TextView? = null
        var loadHiddenIndicator: LoadingIndicatorView? = null
        var hiddenScanLoading = false
        fun setHiddenScanLoading(loading: Boolean) {
            hiddenScanLoading = loading
            refresher.isRefreshing = false
            loadHiddenButton?.apply {
                isEnabled = !loading
                visibility = if (loading) View.INVISIBLE else View.VISIBLE
            }
            loadHiddenIndicator?.visibility = if (loading) View.VISIBLE else View.GONE
        }

        fun hiddenAlbumsInDialog(): List<AlbumItem> =
            mutableAlbums.filter { isHiddenAlbum(it) || hiddenKeys.contains(it.key) }

        fun allAlbumsChecked(): Boolean =
            mutableAlbums.isNotEmpty() && mutableAlbums.all { checkedKeys.contains(it.key) }

        fun hiddenAlbumsChecked(): Boolean {
            val hiddenAlbums = hiddenAlbumsInDialog()
            return hiddenAlbums.isNotEmpty() && hiddenAlbums.all { checkedKeys.contains(it.key) }
        }

        fun updateShowHiddenControl() {
            val allChecked = allAlbumsChecked()
            showHiddenCheck?.isChecked = allChecked
            showHiddenLabel?.setText(
                if (allChecked || hiddenAlbumsChecked()) R.string.main_unselect_hidden else R.string.main_show_hidden
            )
        }

        fun renderAlbums() {
            updateShowHiddenControl()
            listAdapter.notifyDataSetChanged()
        }

        fun requestHiddenScanAccess() {
            setHiddenScanLoading(false)
            Ui.toast(this, getString(R.string.access_hidden_folders_required))
            accessCoordinator.ensureFullAccess(true)
        }

        fun refreshHiddenAlbums() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
                requestHiddenScanAccess()
                return
            }
            catalogController.refreshCatalog(
                owner = this,
                includeHidden = true,
                force = true,
                onSuccess = {
                    mediaLoader.execute {
                        val refreshed = HiddenAlbumClassification.classify(applicationContext, MediaStoreRepository.buildAlbums(
                            GalleryCatalogStore.readMedia(applicationContext, true)
                        )).toMutableList()
                        sortAlbums(refreshed)
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            val previousVisible = HashSet(checkedKeys)
                            mutableAlbums.clear()
                            mutableAlbums.addAll(refreshed)
                            automaticallyHiddenKeys = refreshed.filter { it.naturallyHidden }.mapTo(HashSet()) { it.key }
                            sortVisibilityAlbums()
                            checkedKeys.clear()
                            for (album in mutableAlbums) {
                                if (previousVisible.contains(album.key) || (!hiddenKeys.contains(album.key) && !isHiddenAlbum(album))) {
                                    checkedKeys.add(album.key)
                                }
                            }
                            renderAlbums()
                            setHiddenScanLoading(false)
                        }
                    }
                },
                onFailure = {
                    setHiddenScanLoading(false)
                    Ui.toast(this, getString(R.string.main_hidden_refresh_failed))
                }
            )
        }

        refresher.setOnRefreshListener {
            refresher.isRefreshing = false
            Ui.toast(this, getString(R.string.main_hidden_refresh_instruction))
        }
        renderAlbums()

        var countsRefreshPending = false
        var countsRefreshAgain = false
        val refreshCounts: () -> Unit = {
            if (countsRefreshPending) {
                countsRefreshAgain = true
            } else {
                countsRefreshPending = true
                val snapshot = mutableAlbums.toList()
                mediaLoader.execute {
                    val updated = AlbumCountRefresh.refresh(applicationContext, snapshot)
                    runOnUiThread {
                        countsRefreshPending = false
                        if (!isFinishing && visibilityDialogAdapter === listAdapter) {
                            val countedKeys = snapshot.mapTo(HashSet()) { it.key }
                            val countsByKey = updated.associateBy { it.key }
                            val reconciled = mutableAlbums.mapNotNull { album ->
                                if (album.key in countedKeys) countsByKey[album.key] else album
                            }
                            mutableAlbums.clear()
                            mutableAlbums.addAll(reconciled)
                            sortVisibilityAlbums()
                            renderAlbums()
                            if (countsRefreshAgain) {
                                countsRefreshAgain = false
                                refreshVisibilityDialogCounts?.invoke()
                            }
                        }
                    }
                }
            }
        }
        refreshVisibilityDialogCounts = refreshCounts

        fun loadHiddenAlbums() {
            if (hiddenScanLoading) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
                requestHiddenScanAccess()
                return
            }
            setHiddenScanLoading(true)
            refreshHiddenAlbums()
        }

        fun toggleShowHiddenSelection() {
            if (allAlbumsChecked() || hiddenAlbumsChecked()) {
                for (album in hiddenAlbumsInDialog()) {
                    checkedKeys.remove(album.key)
                }
            } else {
                for (album in mutableAlbums) {
                    checkedKeys.add(album.key)
                }
            }
            renderAlbums()
        }

        fun applyFolderVisibility() {
            val nextHidden = HashSet(hiddenKeys)
            for (album in mutableAlbums) {
                if (checkedKeys.contains(album.key)) {
                    nextHidden.remove(album.key)
                    TemporaryAlbumVisibility.hide(album.key)
                } else {
                    nextHidden.add(album.key)
                }
            }
            showHiddenFolders = mutableAlbums.any { checkedKeys.contains(it.key) && isHiddenAlbum(it) }
            everVisibleKeys.addAll(checkedKeys.filterNot(VirtualAlbumRules::isVirtual))
            prefs.edit()
                .putStringSet("hidden_folder_keys", nextHidden)
                .putStringSet(PREF_EVER_VISIBLE_FOLDER_KEYS, HashSet(everVisibleKeys))
                .putBoolean("show_hidden_folders", showHiddenFolders)
                .apply()
            loadAlbums()
            scheduleRevealExpiry()
            dialog.dismiss()
        }

        fun dialogButton(text: String, primary: Boolean = false, onClick: () -> Unit): TextView =
            TextView(this).apply {
                this.text = text
                textSize = 14f
                setTextColor(dialogText)
                if (primary) setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                minimumHeight = Ui.dp(this@MainActivity, 50)
                isClickable = true
                isFocusable = true
                background = if (primary) {
                    Ui.rounded(Ui.blend(dialogBg, dialogText, 0.10f), 0, this@MainActivity)
                } else {
                    Ui.rounded(Color.TRANSPARENT, 0, this@MainActivity)
                }
                setPadding(
                    Ui.dp(this@MainActivity, 14),
                    Ui.dp(this@MainActivity, 8),
                    Ui.dp(this@MainActivity, 14),
                    Ui.dp(this@MainActivity, 8)
                )
                setOnClickListener { onClick() }
            }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                Ui.dp(this@MainActivity, 22),
                Ui.dp(this@MainActivity, 16),
                Ui.dp(this@MainActivity, 22),
                Ui.dp(this@MainActivity, 54)
            )
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.main_folder_visibility)
                    textSize = 18f
                    setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    setTextColor(dialogText)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.main_hidden_dialog_help)
                    textSize = 14f
                    setTextColor(dialogText)
                    alpha = 0.78f
                    setPadding(0, Ui.dp(this@MainActivity, 5), 0, Ui.dp(this@MainActivity, 6))
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(
                        LinearLayout(this@MainActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            isClickable = true
                            setOnClickListener { toggleShowHiddenSelection() }
                            val checkbox = CheckBox(this@MainActivity).apply {
                                buttonTintList = android.content.res.ColorStateList.valueOf(dialogText)
                                isClickable = false
                                isFocusable = false
                            }
                            showHiddenCheck = checkbox
                            val label = TextView(this@MainActivity).apply {
                                setText(R.string.main_show_hidden)
                                textSize = 15f
                                setTextColor(dialogText)
                                setPadding(Ui.dp(this@MainActivity, 8), 0, 0, 0)
                            }
                            showHiddenLabel = label
                            addView(checkbox)
                            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        },
                        LinearLayout.LayoutParams(0, Ui.dp(this@MainActivity, 48), 1f)
                    )
                    addView(
                        FrameLayout(this@MainActivity).apply {
                          addView(dialogButton(getString(R.string.main_load_hidden)) { loadHiddenAlbums() }.apply {
                            loadHiddenButton = this
                            textSize = 13f
                            setPadding(
                                Ui.dp(this@MainActivity, 8),
                                Ui.dp(this@MainActivity, 8),
                                Ui.dp(this@MainActivity, 8),
                                Ui.dp(this@MainActivity, 8)
                            )
                          }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                          addView(LoadingIndicatorView(this@MainActivity, dialogText).apply {
                              loadHiddenIndicator = this
                              visibility = View.GONE
                          }, FrameLayout.LayoutParams(Ui.dp(this@MainActivity, 32), Ui.dp(this@MainActivity, 32), Gravity.CENTER))
                        },
                        LinearLayout.LayoutParams(0, Ui.dp(this@MainActivity, 48), 1f)
                    )
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(refresher, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.blend(dialogBg, Color.WHITE, 0.03f), 0, this@MainActivity)
            addView(
                Ui.markPrimaryDialogAction(
                    dialogButton(getString(R.string.action_ok), primary = true) { applyFolderVisibility() }
                ),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
        }
        val panel = FrameLayout(this).apply {
            background = Ui.rounded(dialogBg, 14, this@MainActivity)
            clipToOutline = true
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(
                buttonBar,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@MainActivity, 50), Gravity.BOTTOM)
            )
        }

        dialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()
        dialog.setOnDismissListener {
            visibilityDialogAdapter = null
            refreshVisibilityDialogCounts = null
            mergeVisibleAlbumsIntoDialog = null
            mediaRefreshHandler.removeCallbacks(visibilityCountRefreshRunnable)
        }
        Ui.showCenteredPanel(dialog, fullHeight = true)
        // Counts are reconciled asynchronously; checkbox/pin/reveal choices are
        // never overwritten by a late metadata delivery.
        refreshCounts()
        val update: (List<AlbumItem>) -> Unit = { remembered ->
            if (dialog.isShowing && visibilityDialogAdapter === listAdapter) {
                val existing = mutableAlbums.associateBy { it.key }
                for (album in remembered) {
                    if (VirtualAlbumRules.isVirtual(album.key)) continue
                    val index = mutableAlbums.indexOfFirst { it.key == album.key }
                    if (index >= 0) mutableAlbums[index] = album
                    else if (album.key !in existing) {
                        mutableAlbums.add(album)
                        if (!hiddenKeys.contains(album.key) && (showHiddenFolders || !isHiddenAlbum(album))) {
                            checkedKeys.add(album.key)
                        }
                    }
                }
                sortVisibilityAlbums()
                renderAlbums()
                refreshCounts()
            }
        }
        mergeVisibleAlbumsIntoDialog = update
        return update
    }

    private fun setColumnCount(nextCount: Int) {
        val bounded = max(2, min(6, nextCount))
        if (bounded == columnCount) return
        columnCount = bounded
        prefs.edit().putInt("column_count", columnCount).apply()
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

    private fun albumCoverSizePx(): Int {
        val horizontalPadding = Ui.dp(this, 12)
        val itemPadding = Ui.dp(this, 16) * columnCount
        return max(Ui.dp(this, 96), (resources.displayMetrics.widthPixels - horizontalPadding - itemPadding) / columnCount)
    }

    private fun sortAlbums(albums: MutableList<AlbumItem>) {
        AlbumRules.sort(albums, sortMode, sortDesc)
    }

    private fun isHiddenAlbum(album: AlbumItem): Boolean =
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

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else Ui.dp(this, 24)
    }

    private fun navigationBarHeight(): Int {
        val resourceId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else Ui.dp(this, 18)
    }

    private fun shouldIncludeHiddenFilesystem(): Boolean =
        accessCoordinator.includeHiddenFilesystem(
            showHiddenFolders || prefs.getBoolean("always_show_hidden", false) ||
                TemporaryAlbumVisibility.requiresHiddenFilesystem()
        )

    private fun scheduleRevealExpiry() {
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

    private fun animateHiddenControl(view: View) {
        view.animate().cancel()
        view.scaleX = 0.76f
        view.scaleY = 0.76f
        view.animate().scaleX(1f).scaleY(1f).setDuration(190)
            .setInterpolator(OvershootInterpolator(1.5f)).start()
    }

    companion object {
        private const val REQ_BATCH_DELETE = 10
        private const val PREFS = "gallery_albums"
        private const val PREF_PINNED_HIDDEN_FOLDER_KEYS = "pinned_hidden_folder_keys"
        private const val PREF_EVER_VISIBLE_FOLDER_KEYS = "ever_visible_folder_keys"
        private const val DEFERRED_REFRESH_DELAY_MS = 900L
        private const val INITIAL_CATALOG_DELAY_MS = 90L
        private const val SORT_NAME = AlbumRules.SORT_NAME
        private const val SORT_PATH = AlbumRules.SORT_PATH
        private const val SORT_SIZE = AlbumRules.SORT_SIZE
        private const val SORT_MODIFIED = AlbumRules.SORT_MODIFIED
        private const val SORT_CREATED = AlbumRules.SORT_CREATED
        private const val SORT_RANDOM = AlbumRules.SORT_RANDOM
    }

    private data class PendingAlbumSubmission(
        val albums: List<AlbumItem>,
        val query: String
    )
}
