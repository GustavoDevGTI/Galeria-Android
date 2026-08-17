package com.galeria.android

import android.Manifest
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
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
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private lateinit var adapter: AlbumRecyclerAdapter
    private lateinit var emptyView: TextView
    private lateinit var searchInput: EditText
    private lateinit var grid: RecyclerView
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
    private var sortMode = SORT_MODIFIED
    private var sortDesc = true
    private var showImages = true
    private var showVideos = true
    private var showGifs = true
    private var showRaw = true
    private var showSvgs = true
    private var showPortraits = false
    private var showHiddenFolders = false
    private var columnCount = 3
    private var lastColumnGestureMs = 0L
    private val mediaLoader = Executors.newSingleThreadExecutor()
    private var loadGeneration = 0
    private var firstResume = true
    private var mainScreenResumed = false
    private var mediaObserverRefreshPending = false
    private var mediaObserverRefreshScheduled = false
    private var deferredCatalogRefreshPending = false
    private var pendingAlbumSubmission: PendingAlbumSubmission? = null
    private var forceAlbumCoverRefreshOnNextSubmit = false
    private val mediaRefreshHandler = Handler(Looper.getMainLooper())
    private val mediaRefreshRunnable = Runnable {
        mediaObserverRefreshScheduled = false
        if (!isFinishing && mainScreenResumed && hasWindowFocus() && hasReadPermission()) {
            mediaObserverRefreshPending = false
            refreshCatalogWithWorker(shouldIncludeHiddenFilesystem())
        } else {
            mediaObserverRefreshPending = true
        }
    }
    private val mediaObserver = object : ContentObserver(mediaRefreshHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            mediaObserverRefreshPending = true
            if (mainScreenResumed && hasWindowFocus()) scheduleMediaRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        loadSettings()
        buildLayout()
        registerMediaObserver()
        if (hasReadPermission()) {
            ensureInitialFileManagementAccess()
            mediaRefreshHandler.postDelayed({
                if (!isFinishing && hasReadPermission()) {
                    if (GalleryCatalogStore.isCatalogDirty(applicationContext)) {
                        if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = true
                        refreshCatalogWithWorker(shouldIncludeHiddenFilesystem())
                    } else {
                        loadAlbums()
                    }
                }
            }, INITIAL_CATALOG_DELAY_MS)
        } else {
            requestReadPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        mainScreenResumed = true
        if (firstResume) {
            firstResume = false
            return
        }
        loadSettings()
        applyThemeColors()
        if (hasReadPermission()) {
            if (GalleryCatalogStore.isCatalogDirty(applicationContext)) {
                if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = true
                refreshCatalogWithWorker(shouldIncludeHiddenFilesystem())
            } else {
                loadAlbums()
                if (mediaObserverRefreshPending) scheduleMediaRefresh()
            }
        }
    }

    override fun onPause() {
        mainScreenResumed = false
        if (mediaObserverRefreshScheduled) mediaObserverRefreshPending = true
        mediaObserverRefreshScheduled = false
        mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
        try {
            contentResolver.unregisterContentObserver(mediaObserver)
        } catch (_: Exception) {
        }
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
            leftMargin = Ui.dp(this@MainActivity, 3)
            rightMargin = Ui.dp(this@MainActivity, 3)
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
            hint = "Pesquisar pastas"
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
            contentDescription = "Mais opções"
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
        selectAllText = Ui.title(this, "Selecionar tudo", 15).apply {
            setOnClickListener { toggleSelectAll() }
        }
        selectionBar.addView(selectAllText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val cancelSelection = Ui.title(this, "Cancelar", 15).apply {
            gravity = Gravity.RIGHT
            setOnClickListener { exitSelectionMode() }
        }
        selectionBar.addView(cancelSelection, LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 38)))
        selectionBar.visibility = View.GONE

        val content = FrameLayout(this)
        grid = RecyclerView(this).apply {
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
                    .filter { it != "all_media" }
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

        emptyView = Ui.label(this, "Nenhuma pasta encontrada.").apply {
            visibility = View.GONE
        }
        content.addView(
            emptyView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        selectionActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.bg(this@MainActivity))
            setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 6), Ui.dp(this@MainActivity, 12), navigationBarHeight() + Ui.dp(this@MainActivity, 8))
        }
        selectionActionDock = Ui.selectionActionDock(this)
        selectionActions.addView(selectionActionDock, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addSelectionAction(R.drawable.ic_share, "Compartilhar") { shareSelectedAlbums() }
        addSelectionAction(R.drawable.ic_star, "Favoritar") { favoriteSelectedAlbums() }
        addSelectionAction(R.drawable.ic_trash, "Excluir") { confirmDeleteSelectedAlbums() }
        addSelectionAction(R.drawable.ic_arrow_right, "Mover") { askMoveSelectedAlbums() }
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
        Ui.showPopupOptions(
            anchor,
            listOf(
                "Ordenar por",
                "Filtrar mídia",
                "Organização de pastas",
                "Exibir/ocultar pastas",
                "Criar nova pasta",
                "Configurações",
                "Atualizar"
            )
        ) { selected ->
            when (selected) {
                "Ordenar por" -> showSortDialog()
                "Filtrar mídia" -> showMediaFilterDialog()
                "Organização de pastas" -> showFolderOrganizationDialog()
                "Exibir/ocultar pastas" -> showFolderVisibilityDialog()
                "Criar nova pasta" -> FolderCreationMenu(this) { loadAlbums() }.show()
                "Configurações" -> startActivity(Intent(this, SettingsActivity::class.java))
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
        searchInput.hint = if (active) "${adapter.selectedCount()} selecionados" else "Pesquisar pastas"
        searchInput.isEnabled = !active
        moreButton.setImageResource(if (active) R.drawable.ic_back else R.drawable.ic_more_vertical)
        moreButton.contentDescription = if (active) "Cancelar seleção" else "Mais opções"
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
                    Ui.toast(this, "Nenhuma mídia para compartilhar.")
                    return@runOnUiThread
                }
                val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "*/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(share, "Compartilhar"))
            }
        }
    }

    private fun favoriteSelectedAlbums() {
        val albums = adapter.selectedAlbums()
        if (albums.isEmpty()) return
        mediaLoader.execute {
            val items = mediaForAlbums(albums)
            val favorites = HashSet(prefs.getStringSet("favorites", HashSet()) ?: HashSet())
            for (item in items) {
                favorites.add(item.uri.toString())
            }
            prefs.edit().putStringSet("favorites", favorites).apply()
            runOnUiThread {
                Ui.toast(this, "${items.size} item(ns) adicionados aos favoritos.")
                exitSelectionMode()
            }
        }
    }

    private fun confirmDeleteSelectedAlbums() {
        val albums = adapter.selectedAlbums()
        if (albums.isEmpty()) return
        val visibleCount = albums.sumOf { it.count }
        Ui.showConfirmationDialog(
            this,
            "Excluir álbuns",
            "Tem certeza que deseja excluir as mídias de ${albums.size} álbum(ns)? Cerca de $visibleCount item(ns) serão removidos.",
            "Excluir"
        ) { deleteSelectedAlbums(albums) }
    }

    private fun deleteSelectedAlbums(albums: List<AlbumItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            ensureFileManagementAccess(true)
            return
        }
        mediaLoader.execute {
            var deleted = 0
            for (item in mediaForAlbums(albums)) {
                if (MediaActions.requestPermanentDelete(this, item.uri, REQ_READ) == MediaActions.RESULT_DONE) {
                    deleted++
                }
            }
            runOnUiThread {
                Ui.toast(this, "$deleted item(ns) excluídos.")
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
                    Ui.toast(this, "Nenhum álbum disponível para mover.")
                    return@runOnUiThread
                }
                Ui.showAlbumTargets(moreButton, "Mover para", targets) { album ->
                    moveSelectedAlbums(albums, album.path.ifBlank { album.name })
                }
            }
        }
    }

    private fun moveSelectedAlbums(albums: List<AlbumItem>, folder: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            ensureFileManagementAccess(true)
            return
        }
        mediaLoader.execute {
            var moved = 0
            for (item in mediaForAlbums(albums)) {
                if (MediaActions.moveToFolder(this, item, folder) == MediaActions.RESULT_DONE) {
                    moved++
                }
            }
            runOnUiThread {
                Ui.toast(this, "$moved item(ns) movidos.")
                exitSelectionMode()
                if (moved > 0) {
                    if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = true
                    refreshCatalogWithWorker(shouldIncludeHiddenFilesystem())
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
        for (item in MediaStoreRepository.loadMedia(applicationContext, shouldIncludeHiddenFilesystem())) {
            if ((allMedia || keys.contains(item.albumKey)) && matchesMediaFilter(item)) {
                items.add(item)
            }
        }
        return items
    }

    private fun loadAlbums() {
        val request = ++loadGeneration
        val searchAllFiles = prefs.getBoolean("search_all_files", false)
        val includeHidden = shouldIncludeHiddenFilesystem()
        val hiddenKeys = HashSet(prefs.getStringSet("hidden_folder_keys", HashSet()) ?: HashSet())
        val query = if (::searchInput.isInitialized) searchInput.text.toString() else ""
        if (::adapter.isInitialized && adapter.getCount() == 0 && ::emptyView.isInitialized) {
            emptyView.text = "Carregando mídia..."
            emptyView.visibility = View.VISIBLE
        }

        mediaLoader.execute {
            val cachedSummaries = GalleryCatalogStore.readAlbums(applicationContext, includeHidden)
            if (cachedSummaries.isNotEmpty() && includesAllMediaTypes()) {
                val cachedAlbums = prepareAlbums(cachedSummaries, hiddenKeys, includeHidden, searchAllFiles)
                postAlbums(request, cachedAlbums, query)
            }

            if (cachedSummaries.isEmpty()) {
                val media = MediaStoreRepository.refreshMedia(applicationContext, includeHidden, force = true)
                val albums = buildAlbumsFromMedia(media, hiddenKeys, includeHidden, searchAllFiles)
                postAlbums(request, albums, query)
                return@execute
            }

            if (!includesAllMediaTypes()) {
                val media = MediaStoreRepository.loadMedia(applicationContext, includeHidden)
                val albums = buildAlbumsFromMedia(media, hiddenKeys, includeHidden, searchAllFiles)
                postAlbums(request, albums, query)
            }

            val allFilesAccess = MediaActions.hasAllFilesAccess(applicationContext)
            if (!GalleryCatalogStore.hasFreshCatalog(
                    applicationContext,
                    includeHidden,
                    allFilesAccess,
                    CATALOG_FALLBACK_MAX_AGE_MS
                )
            ) {
                runOnUiThread { scheduleDeferredCatalogRefresh(includeHidden) }
            }
        }
    }

    private fun postAlbums(request: Int, albums: List<AlbumItem>, query: String) {
        val sorted = albums.toMutableList()
        sortAlbums(sorted)
        runOnUiThread {
            if (request != loadGeneration || isFinishing) return@runOnUiThread
            showAlbumsProgressively(sorted, query)
        }
    }

    private fun prepareAlbums(
        source: List<AlbumItem>,
        hiddenKeys: Set<String>,
        includeHidden: Boolean,
        searchAllFiles: Boolean
    ): List<AlbumItem> {
        val visible = source.filter { !hiddenKeys.contains(it.key) && (includeHidden || !isHiddenAlbum(it)) }
        if (!searchAllFiles) return visible
        if (visible.isEmpty()) return emptyList()
        val latestAlbum = visible.maxByOrNull { it.latestDate }
        val latest = latestAlbum?.latestDate ?: 0L
        val first = visible.asSequence().map { it.firstDate }.filter { it > 0L }.minOrNull() ?: latest
        return listOf(
            AlbumItem(
                "all_media",
                "Todos os arquivos",
                visible.sumOf { it.count },
                latestAlbum?.cover,
                latest,
                first,
                visible.sumOf { max(0L, it.totalSize) },
                ""
            )
        )
    }

    private fun buildAlbumsFromMedia(
        media: List<MediaItem>,
        hiddenKeys: Set<String>,
        includeHidden: Boolean,
        searchAllFiles: Boolean
    ): List<AlbumItem> {
        val filteredMedia = media.filter(::matchesMediaFilter)
        return prepareAlbums(
            MediaStoreRepository.buildAlbums(filteredMedia),
            hiddenKeys,
            includeHidden,
            searchAllFiles
        )
    }

    private fun includesAllMediaTypes(): Boolean =
        (showImages || showPortraits) && showVideos && showGifs && showRaw && showSvgs

    private fun scheduleDeferredCatalogRefresh(includeHidden: Boolean) {
        if (deferredCatalogRefreshPending || isFinishing || !::grid.isInitialized) return
        deferredCatalogRefreshPending = true
        grid.postDelayed({
            if (isFinishing) {
                deferredCatalogRefreshPending = false
            } else if (!hasWindowFocus()) {
                deferredCatalogRefreshPending = false
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
        rememberVisibleFolderKeys(albums)
        adapter.submit(albums, query)
        if (forceAlbumCoverRefreshOnNextSubmit) {
            forceAlbumCoverRefreshOnNextSubmit = false
            adapter.refreshVisibleCovers()
        }
        updateEmptyText()
        if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
    }

    private fun rememberVisibleFolderKeys(albums: Collection<AlbumItem>) {
        val previous = prefs.getStringSet(PREF_EVER_VISIBLE_FOLDER_KEYS, HashSet()) ?: HashSet()
        val updated = HiddenAlbumDialogRules.rememberVisible(previous, albums.map { it.key })
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
            selectionActions.setBackgroundColor(Ui.bg(this))
            Ui.restyleSelectionActionDock(selectionActionDock)
        }
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun showSortDialog() {
        val labels = arrayOf("Nome", "Caminho", "Tamanho", "Data de modificação", "Data de criação", "Aleatório")
        val modes = arrayOf(SORT_NAME, SORT_PATH, SORT_SIZE, SORT_MODIFIED, SORT_CREATED, SORT_RANDOM)
        var checked = modes.indexOf(sortMode).takeIf { it >= 0 } ?: 3

        Ui.showChoiceDialog(
            this,
            "Ordenar por",
            labels,
            checked,
            neutralText = if (sortDesc) "Decrescente" else "Crescente",
            onNeutral = {
                sortDesc = !sortDesc
                saveSorting()
                loadAlbums()
            }
        ) { which ->
            checked = which
            sortMode = modes[checked]
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
        val labels = arrayOf("Imagens", "Vídeos", "GIFs", "Imagens RAW", "SVGs", "Retratos")
        val checked = booleanArrayOf(showImages, showVideos, showGifs, showRaw, showSvgs, showPortraits)
        Ui.showMultiChoiceDialog(this, "Filtrar mídia", labels, checked) { selected ->
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
        val labels = arrayOf("2 colunas", "3 colunas", "4 colunas", "5 colunas", "6 colunas")
        val checked = max(0, min(4, columnCount - 2))
        Ui.showChoiceDialog(
            this,
            "Organização de pastas",
            labels,
            checked,
            message = "Use dois dedos na grade: juntar diminui as capas; afastar aumenta as capas."
        ) { which ->
            setColumnCount(which + 2)
        }
    }

    private fun showFolderVisibilityDialog() {
        val currentAlbums = adapter.visibleAlbumsSnapshot().filter { it.key != "all_media" }
        rememberVisibleFolderKeys(currentAlbums)
        val previouslyVisibleKeys = HashSet(
            prefs.getStringSet(PREF_EVER_VISIBLE_FOLDER_KEYS, HashSet()) ?: HashSet()
        )
        val hiddenKeys = HashSet(prefs.getStringSet("hidden_folder_keys", HashSet()) ?: HashSet())
        mediaLoader.execute {
            val visibleCatalog = GalleryCatalogStore.readAlbums(applicationContext, false)
            val migratedVisibleKeys = visibleCatalog
                .filter { hiddenKeys.contains(it.key) && !isHiddenAlbum(it) }
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
            for (album in visibleCatalog) {
                if (allowedKeys.contains(album.key)) rememberedByKey[album.key] = album
            }
            for (album in GalleryCatalogStore.readAlbums(applicationContext, true)) {
                if (allowedKeys.contains(album.key)) rememberedByKey[album.key] = album
            }
            val rememberedAlbums = rememberedByKey.values
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val albumsByKey = LinkedHashMap<String, AlbumItem>()
                for (album in rememberedAlbums) albumsByKey[album.key] = album
                for (album in currentAlbums) albumsByKey[album.key] = album
                val albums = albumsByKey.values.toMutableList()
                sortAlbums(albums)
                showFolderVisibilityDialog(albums)
            }
        }
    }

    private fun showFolderVisibilityDialog(albums: List<AlbumItem>) {
        val hiddenKeys = HashSet(prefs.getStringSet("hidden_folder_keys", HashSet()) ?: HashSet())
        val pinnedKeys = HashSet(prefs.getStringSet(PREF_PINNED_HIDDEN_FOLDER_KEYS, HashSet()) ?: HashSet())
        val everVisibleKeys = HashSet(
            prefs.getStringSet(PREF_EVER_VISIBLE_FOLDER_KEYS, HashSet()) ?: HashSet()
        )
        everVisibleKeys.addAll(albums.map { it.key }.filter { it != "all_media" })
        val mutableAlbums = albums.toMutableList()
        val checkedKeys = HashSet<String>()
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
                            setImageResource(R.drawable.ic_star)
                            setBackgroundColor(Color.TRANSPARENT)
                            scaleType = ImageView.ScaleType.CENTER
                            contentDescription = "Fixar nos ocultos"
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
                        }
                    )
                }
                val album = getItem(position)
                val label = row.findViewWithTag<TextView>("label")
                val pin = row.findViewWithTag<ImageButton>("pin")
                val check = row.findViewWithTag<CheckBox>("check")
                val pinned = pinnedKeys.contains(album.key)
                label.text = "${album.name} (${album.count})"
                label.setTextColor(dialogText)
                pin.setImageResource(if (pinned) R.drawable.ic_star_filled else R.drawable.ic_star)
                pin.setColorFilter(if (pinned) dialogText else dialogMuted)
                pin.alpha = if (pinned) 1f else 0.48f
                pin.contentDescription = if (pinned) "Desfixar dos ocultos" else "Fixar nos ocultos"
                pin.setOnClickListener {
                    if (pinnedKeys.contains(album.key)) {
                        pinnedKeys.remove(album.key)
                    } else {
                        pinnedKeys.add(album.key)
                    }
                    savePinnedFolders()
                    sortVisibilityAlbums()
                    notifyDataSetChanged()
                    if (pinnedKeys.contains(album.key)) {
                        listView.smoothScrollToPosition(0)
                    }
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
                    notifyDataSetChanged()
                }
                return row
            }
        }
        listView.adapter = listAdapter
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
            showHiddenLabel?.text = if (allChecked || hiddenAlbumsChecked()) "Desmarcar ocultos" else "Exibir ocultos"
        }

        fun renderAlbums() {
            updateShowHiddenControl()
            listAdapter.notifyDataSetChanged()
        }

        fun requestHiddenScanAccess() {
            refresher.isRefreshing = false
            Ui.toast(this, "Permita acesso total aos arquivos para localizar pastas ocultas.")
            ensureFileManagementAccess(true)
        }

        fun refreshHiddenAlbums() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
                requestHiddenScanAccess()
                return
            }
            val workId = MediaScanScheduler.enqueue(applicationContext, includeHidden = true, replace = true)
            observeWorkCompletion(
                workId,
                onSuccess = {
                    mediaLoader.execute {
                        val refreshed = MediaStoreRepository.buildAlbums(
                            GalleryCatalogStore.readMedia(applicationContext, true)
                        ).toMutableList()
                        sortAlbums(refreshed)
                        runOnUiThread {
                            if (isFinishing) return@runOnUiThread
                            val previousVisible = HashSet(checkedKeys)
                            mutableAlbums.clear()
                            mutableAlbums.addAll(refreshed)
                            sortVisibilityAlbums()
                            checkedKeys.clear()
                            for (album in mutableAlbums) {
                                if (previousVisible.contains(album.key) || (!hiddenKeys.contains(album.key) && !isHiddenAlbum(album))) {
                                    checkedKeys.add(album.key)
                                }
                            }
                            renderAlbums()
                            refresher.isRefreshing = false
                        }
                    }
                },
                onFailure = {
                    refresher.isRefreshing = false
                    Ui.toast(this, "Não foi possível atualizar as pastas ocultas.")
                }
            )
        }

        refresher.setOnRefreshListener {
            refresher.isRefreshing = false
            Ui.toast(this, "Use o botão Carregar ocultos para procurar novas pastas.")
        }
        renderAlbums()

        fun loadHiddenAlbums() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
                requestHiddenScanAccess()
                return
            }
            refresher.isRefreshing = true
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

        lateinit var dialog: AlertDialog
        fun applyFolderVisibility() {
            val nextHidden = HashSet(hiddenKeys)
            for (album in mutableAlbums) {
                if (checkedKeys.contains(album.key)) {
                    nextHidden.remove(album.key)
                } else {
                    nextHidden.add(album.key)
                }
            }
            showHiddenFolders = mutableAlbums.any { checkedKeys.contains(it.key) && isHiddenAlbum(it) }
            everVisibleKeys.addAll(checkedKeys.filter { it != "all_media" })
            prefs.edit()
                .putStringSet("hidden_folder_keys", nextHidden)
                .putStringSet(PREF_EVER_VISIBLE_FOLDER_KEYS, HashSet(everVisibleKeys))
                .putBoolean("show_hidden_folders", showHiddenFolders)
                .apply()
            loadAlbums()
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
                    text = "Exibir/ocultar pastas"
                    textSize = 18f
                    setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                    setTextColor(dialogText)
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "Carregue as pastas ocultas, marque o que deve aparecer e confirme em OK."
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
                                text = "Exibir ocultos"
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
                        dialogButton("Carregar ocultos") { loadHiddenAlbums() }.apply {
                            textSize = 13f
                            setPadding(
                                Ui.dp(this@MainActivity, 8),
                                Ui.dp(this@MainActivity, 8),
                                Ui.dp(this@MainActivity, 8),
                                Ui.dp(this@MainActivity, 8)
                            )
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
                dialogButton("OK", primary = true) { applyFolderVisibility() },
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
        Ui.showSidePanel(dialog, fullHeight = true)
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

    private fun isHiddenAlbum(album: AlbumItem): Boolean = AlbumRules.isHidden(album.path, album.key)

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
        val workId = MediaScanScheduler.enqueue(applicationContext, includeHidden, replace = force)
        observeWorkCompletion(
            workId,
            onSuccess = {
                deferredCatalogRefreshPending = false
                mediaObserverRefreshPending = false
                GalleryCatalogStore.clearCatalogDirty(applicationContext)
                forceAlbumCoverRefreshOnNextSubmit = force
                if (!isFinishing) loadAlbums()
            },
            onFailure = {
                deferredCatalogRefreshPending = false
                if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
            }
        )
    }

    private fun observeWorkCompletion(
        workId: UUID,
        onSuccess: () -> Unit,
        onFailure: () -> Unit
    ) {
        val workManager = WorkManager.getInstance(applicationContext)
        val workInfo = workManager.getWorkInfoByIdLiveData(workId)
        val observer = object : Observer<WorkInfo?> {
            override fun onChanged(value: WorkInfo?) {
                value ?: return
                if (!value.state.isFinished) return
                workInfo.removeObserver(this)
                if (value.state == WorkInfo.State.SUCCEEDED) {
                    onSuccess()
                } else {
                    onFailure()
                }
            }
        }
        workInfo.observe(this, observer)
    }

    private fun scheduleMediaRefresh() {
        mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
        mediaObserverRefreshScheduled = true
        mediaRefreshHandler.postDelayed(mediaRefreshRunnable, 700L)
    }

    private fun updateEmptyText() {
        if (!::adapter.isInitialized || !::emptyView.isInitialized) return
        emptyView.text = "Nenhuma pasta encontrada."
        emptyView.visibility = if (adapter.getCount() == 0) View.VISIBLE else View.GONE
    }

    private fun hasReadPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestReadPermission() {
        val permissions = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        requestPermissions(permissions.toTypedArray(), REQ_READ)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ && hasReadPermission()) {
            ensureInitialFileManagementAccess()
            loadAlbums()
        } else {
            emptyView.visibility = View.VISIBLE
            emptyView.text = "Autorize acesso completo a fotos e vídeos para carregar a galeria."
        }
    }

    private fun ensureInitialFileManagementAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || MediaActions.hasAllFilesAccess(this)) return
        if (prefs.getBoolean(PREF_INITIAL_ALL_FILES_REQUESTED, false)) return
        prefs.edit().putBoolean(PREF_INITIAL_ALL_FILES_REQUESTED, true).apply()
        ensureFileManagementAccess(true)
    }

    private fun ensureFileManagementAccess(force: Boolean) {
        if (MediaActions.hasAllFilesAccess(this)) return
        if (!force && prefs.getBoolean(PREF_ALL_FILES_PROMPTED, false)) return
        Ui.showConfirmationDialog(
            this,
            "Permitir gerenciamento de arquivos",
            "Para excluir, mover, copiar e criar pastas no celular, ative o acesso total a arquivos para a Galeria.",
            "Permitir",
            negativeText = "Agora não",
            onNegative = {
                prefs.edit().putBoolean(PREF_ALL_FILES_PROMPTED, true).apply()
            }
        ) {
                prefs.edit().putBoolean(PREF_ALL_FILES_PROMPTED, true).apply()
                MediaActions.requestAllFilesAccess(this)
        }
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
        showHiddenFolders || prefs.getBoolean("always_show_hidden", false)

    companion object {
        private const val REQ_READ = 10
        private const val PREFS = "gallery_albums"
        private const val PREF_ALL_FILES_PROMPTED = "all_files_prompted"
        private const val PREF_INITIAL_ALL_FILES_REQUESTED = "initial_all_files_requested"
        private const val PREF_PINNED_HIDDEN_FOLDER_KEYS = "pinned_hidden_folder_keys"
        private const val PREF_EVER_VISIBLE_FOLDER_KEYS = "ever_visible_folder_keys"
        private const val CATALOG_FALLBACK_MAX_AGE_MS = 6 * 60 * 60 * 1000L
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
