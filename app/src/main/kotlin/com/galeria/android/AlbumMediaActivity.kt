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
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.filter
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class AlbumMediaActivity : ComponentActivity() {
    private lateinit var adapter: MediaRecyclerAdapter
    private lateinit var grid: RecyclerView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var searchInput: EditText
    private lateinit var searchBox: LinearLayout
    private lateinit var selectAllChip: TextView
    private lateinit var moreButton: ImageButton
    private lateinit var emptyView: TextView
    private var albumKey: String? = null
    private var albumName: String = "Álbum"
    private lateinit var prefs: SharedPreferences
    private var gridSpacingDp = 3
    private var dragging = false
    private var dragPosition = -1
    private var savedFirstVisible = 0
    private var draggedView: View? = null
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionActions: LinearLayout
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
    private val mediaLoader = Executors.newSingleThreadExecutor()
    private var loadGeneration = 0
    private var pagingJob: Job? = null
    private var pendingPagedScrollPosition = -1
    private var gridScrollState = RecyclerView.SCROLL_STATE_IDLE
    private var pendingPagingData: PagingData<MediaItem>? = null
    private var pendingMediaRefresh = false
    private var mediaRefreshScheduled = false
    private var gridPoolWarmupRemaining = 0
    private var warmedGridPoolViewType = -1
    private var firstResume = true
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
    private val mediaObserver = object : ContentObserver(mediaRefreshHandler) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            scheduleMediaRefresh()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Ui.PREFS, MODE_PRIVATE)
        albumKey = intent.getStringExtra("album_key")
        albumName = intent.getStringExtra("album_name")?.takeIf { it.isNotEmpty() } ?: "Álbum"
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
        if (firstResume) {
            firstResume = false
            return
        }
        if (::adapter.isInitialized) {
            loadMedia(true)
        }
    }

    override fun onDestroy() {
        pagingJob?.cancel()
        searchHandler.removeCallbacks(searchReload)
        if (::grid.isInitialized) grid.removeCallbacks(gridPoolWarmup)
        super.onDestroy()
        mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
        try {
            contentResolver.unregisterContentObserver(mediaObserver)
        } catch (_: Exception) {
        }
        mediaLoader.shutdownNow()
    }

    private fun refreshCatalogWithWorker() {
        val workId = MediaScanScheduler.enqueue(
            applicationContext,
            shouldIncludeHiddenFilesystem(),
            replace = true
        )
        mediaLoader.execute {
            try {
                androidx.work.WorkManager.getInstance(applicationContext).getWorkInfoById(workId).get()
                if (!Thread.currentThread().isInterrupted) {
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) loadMedia(true)
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } catch (_: Exception) {
                runOnUiThread {
                    if (!isFinishing && ::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
                }
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
            contentDescription = "Voltar"
            setOnClickListener { handleToolbarBack() }
        }
        bar.addView(back, LinearLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46)))

        gridSpacingDp = max(0, min(MAX_GRID_SPACING_DP, prefs.getInt(spacingKey(), 3)))
        searchBox = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            tag = TAG_ALBUM_SEARCH_TITLE
            contentDescription = "Título e pesquisa do álbum"
            setPadding(Ui.dp(this@AlbumMediaActivity, 6), 0, Ui.dp(this@AlbumMediaActivity, 4), 0)
        }
        selectAllChip = Ui.title(this, "[ ]", 16).apply {
            gravity = Gravity.CENTER
            setTextColor(Ui.text(this@AlbumMediaActivity))
            visibility = View.GONE
            setOnClickListener { toggleSelectAll() }
        }
        searchBox.addView(selectAllChip, LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 38)))

        searchInput = EditText(this).apply {
            hint = albumName
            setHintTextColor(Ui.text(this@AlbumMediaActivity))
            setTextColor(Ui.text(this@AlbumMediaActivity))
            textSize = 20f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setSingleLine(true)
            setBackgroundColor(Color.TRANSPARENT)
            isCursorVisible = false
            contentDescription = "Pesquisar nesta pasta"
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
            leftMargin = Ui.dp(this@AlbumMediaActivity, 2)
            rightMargin = Ui.dp(this@AlbumMediaActivity, 2)
        }
        bar.addView(searchBox, searchParams)

        moreButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_more_vertical)
            setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Ui.text(this@AlbumMediaActivity))
            setPadding(Ui.dp(this@AlbumMediaActivity, 9), Ui.dp(this@AlbumMediaActivity, 9), Ui.dp(this@AlbumMediaActivity, 9), Ui.dp(this@AlbumMediaActivity, 9))
            contentDescription = "Mais opções"
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
        selectAllText = Ui.title(this, "[ ] Selecionar tudo", 15).apply {
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
                emptyView.text = "Não foi possível carregar as mídias."
                emptyView.visibility = if (adapter.getCount() == 0) View.VISIBLE else View.GONE
            }
        }
        applyViewMode()
        grid.adapter = adapter
        grid.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN && mediaRefreshScheduled) {
                mediaRefreshHandler.removeCallbacks(mediaRefreshRunnable)
                mediaRefreshScheduled = false
                pendingMediaRefresh = true
            }
            if (!dragging) return@setOnTouchListener false
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

        emptyView = Ui.label(this, "Nenhuma foto ou vídeo nesta pasta.").apply {
            visibility = View.GONE
        }
        content.addView(emptyView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        selectionActions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.bg(this@AlbumMediaActivity))
            setPadding(Ui.dp(this@AlbumMediaActivity, 10), Ui.dp(this@AlbumMediaActivity, 8), Ui.dp(this@AlbumMediaActivity, 10), navigationBarHeight() + Ui.dp(this@AlbumMediaActivity, 8))
        }
        addSelectionAction(R.drawable.ic_share, "Compartilhar") { shareSelected() }
        addSelectionAction(R.drawable.ic_star, "Favoritar") { favoriteSelected() }
        addSelectionAction(R.drawable.ic_trash, "Excluir") { confirmDeleteSelected() }
        addSelectionAction(R.drawable.ic_arrow_right, "Mover") { askMoveSelected() }
        selectionActions.visibility = View.GONE
        root.addView(selectionActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun warmGridPoolGradually() {
        if (!::grid.isInitialized || adapter.getCount() == 0) return
        val viewType = if (listMode) 1 else 0
        if (warmedGridPoolViewType == viewType) return
        warmedGridPoolViewType = viewType
        val target = if (listMode) 4 else mediaSpanCount() * 2
        gridPoolWarmupRemaining = max(gridPoolWarmupRemaining, target)
        grid.removeCallbacks(gridPoolWarmup)
        grid.postDelayed(gridPoolWarmup, GRID_POOL_WARMUP_STEP_MS)
    }

    private fun addSelectionAction(icon: Int, label: String, listener: () -> Unit) {
        val button = ImageButton(this).apply {
            setImageResource(icon)
            setColorFilter(Ui.accent(this@AlbumMediaActivity))
            contentDescription = label
            scaleType = ImageView.ScaleType.CENTER
            setPadding(Ui.dp(this@AlbumMediaActivity, 10), Ui.dp(this@AlbumMediaActivity, 10), Ui.dp(this@AlbumMediaActivity, 10), Ui.dp(this@AlbumMediaActivity, 10))
            background = Ui.rounded(Ui.surface(this@AlbumMediaActivity), 8, this@AlbumMediaActivity)
            setOnClickListener { listener() }
        }
        val params = LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f).apply {
            setMargins(Ui.dp(this@AlbumMediaActivity, 6), 0, Ui.dp(this@AlbumMediaActivity, 6), 0)
        }
        selectionActions.addView(button, params)
    }

    private fun showFolderMenu(anchor: View) {
        Ui.showPopupOptions(
            anchor,
            listOf(
                "Filtrar mídia",
                "Agrupar por",
                "Modo de visualização",
                "Criar nova pasta",
                "Aleatório",
                "Espaçamento da grade"
            )
        ) { selected ->
            when (selected) {
                "Filtrar mídia" -> showMediaFilterDialog()
                "Agrupar por" -> showGroupDialog()
                "Modo de visualização" -> showViewModeDialog()
                "Criar nova pasta" -> showCreateFolderDialog()
                "Aleatório" -> startRandomPlayback()
                else -> showSpacingDialog()
            }
        }
    }

    private fun readAlbumOptions() {
        showImages = prefs.getBoolean(optionKey("filter_images"), true)
        showVideos = prefs.getBoolean(optionKey("filter_videos"), true)
        showGifs = prefs.getBoolean(optionKey("filter_gifs"), true)
        showRaw = prefs.getBoolean(optionKey("filter_raw"), true)
        showSvgs = prefs.getBoolean(optionKey("filter_svg"), true)
        listMode = prefs.getBoolean(optionKey("list_mode"), false)
        groupMode = prefs.getString(optionKey("group_mode"), GROUP_NONE) ?: GROUP_NONE
    }

    private fun showMediaFilterDialog() {
        val labels = arrayOf("Imagens", "Vídeos", "GIFs", "Imagens RAW", "SVGs")
        val checked = booleanArrayOf(showImages, showVideos, showGifs, showRaw, showSvgs)
        Ui.showMultiChoiceDialog(this, "Filtrar mídia", labels, checked) { selected ->
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
        val labels = arrayOf("Não agrupar arquivos", "Tipo de arquivo", "Extensão", "Data da foto (por dia)", "Data da foto (por mês)")
        val values = arrayOf(GROUP_NONE, GROUP_TYPE, GROUP_EXTENSION, GROUP_DAY, GROUP_MONTH)
        var choice = values.indexOf(groupMode).takeIf { it >= 0 } ?: 0
        Ui.showChoiceDialog(this, "Agrupar por", labels, choice) { which ->
            choice = which
            groupMode = values[choice]
            prefs.edit().putString(optionKey("group_mode"), groupMode).apply()
            loadMedia(true)
        }
    }

    private fun showViewModeDialog() {
        val labels = arrayOf("Grade", "Lista")
        var choice = if (listMode) 1 else 0
        Ui.showChoiceDialog(this, "Modo de visualização", labels, choice) { which ->
            choice = which
            listMode = choice == 1
            prefs.edit().putBoolean(optionKey("list_mode"), listMode).apply()
            applyViewMode()
        }
    }

    private fun showCreateFolderDialog() {
        Ui.showTextInputDialog(
            this,
            "Criar nova pasta",
            "Título",
            message = folderDisplayPath()
        ) { name ->
            createFolder(name)
        }
    }

    private fun showSpacingDialog() {
        lateinit var dialog: AlertDialog
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(Ui.surface(this@AlbumMediaActivity), 4, this@AlbumMediaActivity)
            setPadding(
                Ui.dp(this@AlbumMediaActivity, 24),
                Ui.dp(this@AlbumMediaActivity, 20),
                Ui.dp(this@AlbumMediaActivity, 24),
                Ui.dp(this@AlbumMediaActivity, 8)
            )
        }
        panel.addView(
            TextView(this).apply {
                text = "Espaçamento da grade"
                textSize = 20f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(Ui.text(this@AlbumMediaActivity))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        val hint = TextView(this).apply {
            text = "Vale para todas as pastas. Esquerda: mais espaço. Direita: sem espaço."
            textSize = 13f
            setTextColor(Ui.text(this@AlbumMediaActivity))
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
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                setPadding(0, Ui.dp(this@AlbumMediaActivity, 8), 0, 0)
                addView(
                    TextView(this@AlbumMediaActivity).apply {
                        text = "OK"
                        textSize = 14f
                        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                        setTextColor(Ui.text(this@AlbumMediaActivity))
                        gravity = Gravity.CENTER
                        isClickable = true
                        isFocusable = true
                        setPadding(
                            Ui.dp(this@AlbumMediaActivity, 14),
                            Ui.dp(this@AlbumMediaActivity, 12),
                            Ui.dp(this@AlbumMediaActivity, 14),
                            Ui.dp(this@AlbumMediaActivity, 12)
                        )
                        setOnClickListener { dialog.dismiss() }
                    }
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        dialog = AlertDialog.Builder(this).setView(panel).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
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
        selectAllChip.text = if (adapter.allVisibleSelected()) "[x]" else "[ ]"
        searchInput.isEnabled = !active
        moreButton.setImageResource(if (active) R.drawable.ic_back else R.drawable.ic_more_vertical)
        moreButton.contentDescription = if (active) "Cancelar seleção" else "Mais opções"
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
            selecting -> "${adapter.selectedCount()} selecionados"
            searching -> "Pesquisar nesta pasta"
            else -> "Pesquisar em $albumName"
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
        if (selected.isEmpty()) return
        val uris = ArrayList<android.net.Uri>()
        for (item in selected) {
            uris.add(item.uri)
        }
        val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "Compartilhar"))
    }

    private fun favoriteSelected() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        val favorites = HashSet(prefs.getStringSet("favorites", HashSet()) ?: HashSet())
        for (item in selected) {
            favorites.add(item.uri.toString())
        }
        prefs.edit().putStringSet("favorites", favorites).apply()
        Ui.toast(this, "${selected.size} item(ns) adicionados aos favoritos.")
        exitSelectionMode()
    }

    private fun confirmDeleteSelected() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Excluir selecionados")
            .setMessage("Tem certeza que deseja excluir ${selected.size} arquivo(s)?")
            .setPositiveButton("Excluir") { _, _ -> deleteSelected(selected) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun deleteSelected(selected: List<MediaItem>) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            requestFileManagementAccess()
            return
        }
        var deleted = 0
        for (item in selected) {
            if (MediaActions.requestPermanentDelete(this, item.uri, REQ_DELETE) == MediaActions.RESULT_DONE) {
                deleted++
            }
        }
        Ui.toast(this, "$deleted item(ns) excluídos.")
        exitSelectionMode()
    }

    private fun askMoveSelected() {
        val selected = adapter.selectedItems()
        if (selected.isEmpty()) return
        val targets = availableAlbumTargets()
        if (targets.isEmpty()) {
            Ui.toast(this, "Nenhum álbum disponível para mover.")
            return
        }
        Ui.showPopupOptions(moreButton, targets.keys.toList()) { selectedLabel ->
            targets[selectedLabel]?.let { moveSelected(selected, it) }
        }
    }

    private fun moveSelected(selected: List<MediaItem>, folder: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
            requestFileManagementAccess()
            return
        }
        var moved = 0
        for (item in selected) {
            if (MediaActions.moveToFolder(this, item, folder) == MediaActions.RESULT_DONE) {
                moved++
            }
        }
        Ui.toast(this, "$moved item(ns) movidos.")
        exitSelectionMode()
    }

    private fun loadMedia(preserveScroll: Boolean) {
        val request = ++loadGeneration
        val targetPosition = if (preserveScroll) layoutManager.findFirstVisibleItemPosition() else savedFirstVisible
        val query = if (::searchInput.isInitialized) searchInput.text.toString() else ""
        if (groupMode == GROUP_NONE && !adapter.isSelectionMode()) {
            if (adapter.getCount() == 0 && ::emptyView.isInitialized) {
                emptyView.text = "Carregando mídias..."
                emptyView.visibility = View.VISIBLE
            }
            loadPagedMedia(request, query, targetPosition)
            return
        }
        pagingJob?.cancel()
        if (::adapter.isInitialized && adapter.getCount() == 0 && ::emptyView.isInitialized) {
            emptyView.text = "Carregando mídia..."
            emptyView.visibility = View.VISIBLE
        }
        mediaLoader.execute {
            val items = prepareAlbumMedia(
                MediaStoreRepository.loadMediaForAlbum(applicationContext, albumKey, shouldIncludeHiddenFilesystem())
            )
            runOnUiThread {
                if (request != loadGeneration || isFinishing) return@runOnUiThread
                showMedia(items, query, targetPosition)
            }
        }
    }

    private fun loadPagedMedia(request: Int, query: String, targetPosition: Int) {
        pagingJob?.cancel()
        pendingPagedScrollPosition = targetPosition
        pagingJob = lifecycleScope.launch {
            GalleryCatalogStore.pagedMedia(
                applicationContext,
                shouldIncludeHiddenFilesystem(),
                albumKey,
                query,
                PagingConfig(
                    pageSize = 30,
                    initialLoadSize = 45,
                    prefetchDistance = 12,
                    enablePlaceholders = false,
                    maxSize = 180
                )
            ).map { page -> page.filter(::matchesMediaFilter) }
                .collectLatest { page ->
                    if (request == loadGeneration && !isFinishing) {
                        if (gridScrollState == RecyclerView.SCROLL_STATE_IDLE && !dragging) {
                            adapter.submitPagingData(page)
                        } else {
                            pendingPagingData = page
                        }
                    }
                }
        }
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
        adapter.submit(items, query)
        updateEmptyState()
        updateSelectionUi()
        if (::swipeRefresh.isInitialized) swipeRefresh.isRefreshing = false
        if (targetPosition > 0) {
            grid.scrollToPosition(min(targetPosition, max(0, adapter.getCount() - 1)))
        }
    }

    private fun applyCustomOrder(items: List<MediaItem>): List<MediaItem> {
        val saved = GalleryCatalogStore.migrateLegacyOrder(applicationContext, albumKey ?: "all")
        if (saved.isEmpty()) return items
        val byUri = HashMap<String, MediaItem>()
        for (item in items) {
            byUri[item.uri.toString()] = item
        }
        val ordered = ArrayList<MediaItem>()
        val used = HashSet<String>()
        for (line in saved) {
            val item = byUri[line]
            if (item != null) {
                ordered.add(item)
                used.add(line)
            }
        }
        for (item in items) {
            if (!used.contains(item.uri.toString())) {
                ordered.add(item)
            }
        }
        return ordered
    }

    private fun prepareAlbumMedia(source: List<MediaItem>): List<MediaItem> {
        val filtered = ArrayList<MediaItem>()
        for (item in applyCustomOrder(source)) {
            if (matchesMediaFilter(item)) {
                filtered.add(item)
            }
        }
        applyGrouping(filtered)
        return filtered
    }

    private fun matchesMediaFilter(item: MediaItem): Boolean = MediaFilterRules.matches(
        item.name,
        item.mimeType,
        MediaFilterOptions(showImages, showVideos, showGifs, showRaw, showSvgs)
    )

    private fun applyGrouping(items: MutableList<MediaItem>) {
        if (groupMode == GROUP_NONE) return
        items.sortWith { first, second ->
            val group = groupValue(first).compareTo(groupValue(second))
            if (group != 0) group else second.dateAdded.compareTo(first.dateAdded)
        }
    }

    private fun groupValue(item: MediaItem): String =
        when (groupMode) {
            GROUP_TYPE -> if (item.isVideo()) "2_video" else "1_image"
            GROUP_EXTENSION -> fileExtension(item.name)
            GROUP_DAY -> dateGroup(item.dateAdded, true)
            GROUP_MONTH -> dateGroup(item.dateAdded, false)
            else -> ""
        }

    private fun fileExtension(name: String?): String {
        val dot = name?.lastIndexOf('.') ?: -1
        return if (name != null && dot >= 0 && dot + 1 < name.length) name.substring(dot + 1).lowercase(Locale.US) else ""
    }

    private fun dateGroup(seconds: Long, includeDay: Boolean): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = max(0L, seconds) * 1000L
        val year = calendar.get(Calendar.YEAR)
        val month = calendar.get(Calendar.MONTH) + 1
        val day = if (includeDay) calendar.get(Calendar.DAY_OF_MONTH) else 0
        return String.format(Locale.US, "%04d-%02d-%02d", year, month, day)
    }

    private fun saveCustomOrder() {
        val order = adapter.currentOrder()
        mediaLoader.execute {
            GalleryCatalogStore.saveCustomOrder(applicationContext, albumKey ?: "all", order)
            runOnUiThread {
                if (!isFinishing) Ui.toast(this, "Ordem personalizada salva.")
            }
        }
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
            Ui.toast(this, "Nenhuma mídia para reproduzir.")
            return
        }
        val position = Random().nextInt(adapter.getCount())
        openDetail(adapter.getItem(position), position, true)
    }

    private fun applyViewMode() {
        if (!::grid.isInitialized || !::adapter.isInitialized) return
        layoutManager.spanCount = if (listMode) 1 else mediaSpanCount()
        adapter.setListMode(listMode)
        applyGridSpacing()
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
        return if (path.isEmpty()) "Armazenamento interno" else "Armazenamento interno/$path"
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
            Ui.toast(this, "Digite um nome para a pasta.")
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
            Ui.toast(this, "A pasta já existe.")
            return
        }
        if (MediaActions.createFolder(this, target)) {
            Ui.toast(this, "Pasta criada.")
        } else {
            Ui.toast(this, "Não foi possível criar a pasta.")
        }
    }

    private fun requestFileManagementAccess() {
        AlertDialog.Builder(this)
            .setTitle("Permitir gerenciamento de arquivos")
            .setMessage("Para criar pastas, mover e excluir arquivos no celular, ative o acesso total a arquivos para a Galeria.")
            .setPositiveButton("Permitir") { _, _ -> MediaActions.requestAllFilesAccess(this) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun availableAlbumTargets(): LinkedHashMap<String, String> {
        val targets = LinkedHashMap<String, String>()
        for (album in MediaStoreRepository.loadAlbums(applicationContext, shouldIncludeHiddenFilesystem())) {
            if (album.key != "all_media" && album.key != albumKey && album.name.isNotBlank()) {
                val path = album.path.ifBlank { album.name }
                val label = if (album.path.isNotBlank()) "${album.name} - ${album.path}" else album.name
                if (!targets.containsKey(label)) targets[label] = path
            }
        }
        return targets
    }

    private fun shouldIncludeHiddenFilesystem(): Boolean =
        intent.getBooleanExtra("include_hidden_filesystem", false) ||
            prefs.getBoolean("always_show_hidden", false)

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
        }
        startActivity(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DELETE) {
            if (resultCode == RESULT_OK) {
                Ui.toast(this, "Item excluído.")
            }
            loadMedia(true)
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
        private const val MAX_GRID_SPACING_DP = 8
        private const val INITIAL_MEDIA_DELAY_MS = 60L
        private const val GRID_POOL_WARMUP_STEP_MS = 24L
        private const val GRID_POOL_WARMUP_RETRY_MS = 80L
        private const val MEDIA_OBSERVER_GRACE_MS = 3_000L
        private const val MEDIA_REFRESH_DEBOUNCE_MS = 5_000L
        private const val GROUP_NONE = "none"
        private const val GROUP_TYPE = "type"
        private const val GROUP_EXTENSION = "extension"
        private const val GROUP_DAY = "day"
        private const val GROUP_MONTH = "month"
    }
}
