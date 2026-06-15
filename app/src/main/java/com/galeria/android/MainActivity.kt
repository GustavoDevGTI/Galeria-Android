package com.galeria.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale
import java.util.Random
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private lateinit var adapter: AlbumRecyclerAdapter
    private lateinit var emptyView: TextView
    private lateinit var searchInput: EditText
    private lateinit var grid: RecyclerView
    private lateinit var layoutManager: GridLayoutManager
    private lateinit var root: LinearLayout
    private lateinit var top: LinearLayout
    private lateinit var selectionBar: LinearLayout
    private lateinit var selectionActions: LinearLayout
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        loadSettings()
        buildLayout()
        if (hasReadPermission()) {
            ensureFileManagementAccess(false)
            loadAlbums()
        } else {
            requestReadPermission()
        }
    }

    override fun onResume() {
        super.onResume()
        loadSettings()
        applyThemeColors()
        if (hasReadPermission()) {
            loadAlbums()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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

        val searchIcon = iconButton(R.drawable.ic_search).apply {
            setOnClickListener {
                searchInput.requestFocus()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        top.addView(searchIcon, LinearLayout.LayoutParams(Ui.dp(this, 36), Ui.dp(this, 38)))

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

        val more = iconButton(R.drawable.ic_more_vertical).apply {
            setOnClickListener { showMenu(it) }
        }
        top.addView(more, LinearLayout.LayoutParams(Ui.dp(this, 30), Ui.dp(this, 38)))

        selectionBar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(Ui.surface(this@MainActivity), 8, this@MainActivity)
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
        val selectionParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(Ui.dp(this@MainActivity, 14), 0, Ui.dp(this@MainActivity, 14), Ui.dp(this@MainActivity, 8))
        }
        root.addView(selectionBar, selectionParams)

        val content = FrameLayout(this)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, columnCount).also { this@MainActivity.layoutManager = it }
            clipToPadding = false
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
                val intent = Intent(this@MainActivity, AlbumMediaActivity::class.java).apply {
                    putExtra("album_key", album.key)
                    putExtra("album_name", album.name)
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
        grid.adapter = adapter
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
        content.addView(grid)

        emptyView = Ui.label(this, "Nenhuma pasta encontrada.").apply {
            visibility = View.GONE
        }
        content.addView(
            emptyView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        selectionActions = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(Ui.bg(this@MainActivity))
            Ui.setPadding(this, 10, 8, 10, 12)
        }
        addSelectionAction("Compartilhar") { shareSelectedAlbums() }
        addSelectionAction("Favoritar") { favoriteSelectedAlbums() }
        addSelectionAction("Excluir") { confirmDeleteSelectedAlbums() }
        addSelectionAction("Mover") { askMoveSelectedAlbums() }
        selectionActions.visibility = View.GONE
        root.addView(selectionActions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        setContentView(root)
    }

    private fun addSelectionAction(label: String, listener: () -> Unit) {
        val button = Ui.title(this, label, 14).apply {
            setTextColor(Ui.accent(this@MainActivity))
            gravity = Gravity.CENTER
            background = Ui.rounded(Ui.surface(this@MainActivity), 8, this@MainActivity)
            setOnClickListener { listener() }
        }
        val params = LinearLayout.LayoutParams(0, Ui.dp(this, 42), 1f).apply {
            setMargins(Ui.dp(this@MainActivity, 4), 0, Ui.dp(this@MainActivity, 4), 0)
        }
        selectionActions.addView(button, params)
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
        val menu = PopupMenu(this, anchor)
        menu.menu.add("Ordenar por")
        menu.menu.add("Filtrar mídia")
        menu.menu.add("Organização de pastas")
        menu.menu.add("Exibir/ocultar pastas")
        menu.menu.add("Criar nova pasta")
        menu.menu.add("Configurações")
        menu.menu.add(if (showHiddenFolders) "Ocultar ocultos" else "Exibir ocultos")
        menu.menu.add("Atualizar")
        menu.setOnMenuItemClickListener { item ->
            when (item.title.toString()) {
                "Ordenar por" -> showSortDialog()
                "Filtrar mídia" -> showMediaFilterDialog()
                "Organização de pastas" -> showFolderOrganizationDialog()
                "Exibir/ocultar pastas" -> showFolderVisibilityDialog()
                "Criar nova pasta" -> startActivity(Intent(this, FolderPickerActivity::class.java))
                "Configurações" -> startActivity(Intent(this, SettingsActivity::class.java))
                "Exibir ocultos" -> {
                    showHiddenFolders = true
                    prefs.edit().putBoolean("show_hidden_folders", true).apply()
                    loadAlbums()
                }
                "Ocultar ocultos" -> {
                    showHiddenFolders = false
                    prefs.edit()
                        .putBoolean("show_hidden_folders", false)
                        .putBoolean("always_show_hidden", false)
                        .apply()
                    loadAlbums()
                }
                else -> loadAlbums()
            }
            true
        }
        menu.show()
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
        selectionBar.visibility = if (active) View.VISIBLE else View.GONE
        selectionActions.visibility = if (active) View.VISIBLE else View.GONE
        selectAllText.text = (if (adapter.allVisibleSelected()) "[x] " else "[ ] ") +
            "Selecionar tudo" +
            if (adapter.selectedCount() > 0) " (${adapter.selectedCount()})" else ""
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
        AlertDialog.Builder(this)
            .setTitle("Excluir álbuns")
            .setMessage("Tem certeza que deseja excluir as mídias de ${albums.size} álbum(ns)? Cerca de $visibleCount item(ns) serão removidos.")
            .setPositiveButton("Excluir") { _, _ -> deleteSelectedAlbums(albums) }
            .setNegativeButton("Cancelar", null)
            .show()
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
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "Ex.: Viagens"
            setTextColor(Ui.text(this@MainActivity))
            setHintTextColor(Ui.muted(this@MainActivity))
        }
        AlertDialog.Builder(this)
            .setTitle("Mover álbuns")
            .setMessage("Digite o nome da pasta de destino.")
            .setView(input)
            .setPositiveButton("Mover") { _, _ -> moveSelectedAlbums(albums, input.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
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
                loadAlbums()
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
        for (item in MediaStoreRepository.loadMedia(applicationContext)) {
            if ((allMedia || keys.contains(item.albumKey)) && matchesMediaFilter(item)) {
                items.add(item)
            }
        }
        return items
    }

    private fun loadAlbums() {
        val request = ++loadGeneration
        val searchAllFiles = prefs.getBoolean("search_all_files", false)
        val includeHidden = showHiddenFolders
        val hiddenKeys = HashSet(prefs.getStringSet("hidden_folder_keys", HashSet()) ?: HashSet())
        val query = if (::searchInput.isInitialized) searchInput.text.toString() else ""
        if (::adapter.isInitialized && adapter.getCount() == 0 && ::emptyView.isInitialized) {
            val cachedAlbums = MediaCatalogCache.readAlbums(this)
            if (cachedAlbums.isNotEmpty()) {
                adapter.submit(cachedAlbums)
                adapter.applyFilter(query)
                updateEmptyText()
            } else {
                emptyView.text = "Carregando mídia..."
                emptyView.visibility = View.VISIBLE
            }
        }

        mediaLoader.execute {
            val media = MediaStoreRepository.loadMedia(applicationContext)
            val filteredMedia = media.filter { matchesMediaFilter(it) }
            var albums: List<AlbumItem>
            if (searchAllFiles) {
                val cover = filteredMedia.firstOrNull()
                var latest = 0L
                var first = Long.MAX_VALUE
                var size = 0L
                for (item in filteredMedia) {
                    latest = max(latest, item.dateAdded)
                    if (item.dateAdded > 0) {
                        first = min(first, item.dateAdded)
                    }
                    size += max(0L, item.size)
                }
                albums = listOf(
                    AlbumItem(
                        "all_media",
                        "Todos os arquivos",
                        filteredMedia.size,
                        cover,
                        latest,
                        if (first == Long.MAX_VALUE) latest else first,
                        size,
                        ""
                    )
                )
            } else {
                albums = MediaStoreRepository.buildAlbums(filteredMedia)
            }
            if (!includeHidden) {
                albums = albums.filter { !hiddenKeys.contains(it.key) && !isHiddenAlbum(it) }
            }
            val sorted = albums.toMutableList()
            sortAlbums(sorted)
            MediaCatalogCache.writeAlbums(applicationContext, sorted)
            runOnUiThread {
                if (request != loadGeneration || isFinishing) return@runOnUiThread
                showAlbumsProgressively(sorted, query)
            }
        }
    }

    private fun showAlbumsProgressively(albums: List<AlbumItem>, query: String) {
        if (albums.size <= 60 || !::grid.isInitialized) {
            adapter.submit(albums)
            adapter.applyFilter(query)
            updateEmptyText()
            return
        }
        adapter.submit(ArrayList(albums.subList(0, 60)))
        adapter.applyFilter(query)
        updateEmptyText()
        grid.postDelayed({
            adapter.submit(albums)
            adapter.applyFilter(query)
            updateEmptyText()
        }, 120)
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
        if (::grid.isInitialized) grid.setBackgroundColor(Ui.bg(this))
        if (::selectionBar.isInitialized) selectionBar.background = Ui.rounded(Ui.surface(this), 8, this)
        if (::selectionActions.isInitialized) {
            selectionActions.setBackgroundColor(Ui.bg(this))
            for (i in 0 until selectionActions.childCount) {
                val child = selectionActions.getChildAt(i)
                child.background = Ui.rounded(Ui.surface(this), 8, this)
                if (child is TextView) {
                    child.setTextColor(Ui.accent(this))
                }
            }
        }
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }

    private fun showSortDialog() {
        val labels = arrayOf("Nome", "Caminho", "Tamanho", "Data de modificação", "Data de criação", "Aleatório")
        val modes = arrayOf(SORT_NAME, SORT_PATH, SORT_SIZE, SORT_MODIFIED, SORT_CREATED, SORT_RANDOM)
        var checked = modes.indexOf(sortMode).takeIf { it >= 0 } ?: 3

        AlertDialog.Builder(this)
            .setTitle("Ordenar por")
            .setSingleChoiceItems(labels, checked) { _, which ->
                sortMode = modes[which]
                checked = which
            }
            .setNegativeButton("Cancelar", null)
            .setNeutralButton(if (sortDesc) "Decrescente" else "Crescente") { _, _ ->
                sortDesc = !sortDesc
                saveSorting()
                loadAlbums()
            }
            .setPositiveButton("OK") { _, _ ->
                sortMode = modes[checked]
                saveSorting()
                loadAlbums()
            }
            .show()
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
        AlertDialog.Builder(this)
            .setTitle("Filtrar mídia")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("OK") { _, _ ->
                showImages = checked[0]
                showVideos = checked[1]
                showGifs = checked[2]
                showRaw = checked[3]
                showSvgs = checked[4]
                showPortraits = checked[5]
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
            .show()
    }

    private fun showFolderOrganizationDialog() {
        val labels = arrayOf("2 colunas", "3 colunas", "4 colunas", "5 colunas", "6 colunas")
        val checked = max(0, min(4, columnCount - 2))
        AlertDialog.Builder(this)
            .setTitle("Organização de pastas")
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                setColumnCount(which + 2)
                dialog.dismiss()
            }
            .setMessage("Use dois dedos na grade: juntar diminui as capas; afastar aumenta as capas.")
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showFolderVisibilityDialog() {
        val albums = MediaStoreRepository.buildAlbums(MediaStoreRepository.loadMedia(this)).toMutableList()
        sortAlbums(albums)
        val hiddenKeys = HashSet(prefs.getStringSet("hidden_folder_keys", HashSet()) ?: HashSet())
        val labels = arrayOfNulls<String>(albums.size + 1)
        val checked = BooleanArray(albums.size + 1)
        labels[0] = "Exibir pastas ocultas temporariamente"
        checked[0] = showHiddenFolders
        for (i in albums.indices) {
            val album = albums[i]
            labels[i + 1] = "${album.name} (${album.count})"
            checked[i + 1] = !hiddenKeys.contains(album.key)
        }

        AlertDialog.Builder(this)
            .setTitle("Exibir/ocultar pastas")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setNeutralButton("Mostrar todas") { _, _ ->
                prefs.edit()
                    .putStringSet("hidden_folder_keys", HashSet())
                    .putBoolean("show_hidden_folders", false)
                    .apply()
                showHiddenFolders = false
                loadAlbums()
            }
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("OK") { _, _ ->
                val nextHidden = HashSet<String>()
                for (i in albums.indices) {
                    if (!checked[i + 1]) {
                        nextHidden.add(albums[i].key)
                    }
                }
                showHiddenFolders = checked[0]
                prefs.edit()
                    .putStringSet("hidden_folder_keys", nextHidden)
                    .putBoolean("show_hidden_folders", showHiddenFolders)
                    .apply()
                loadAlbums()
            }
            .show()
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
                    grid.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(130).start()
                }
                .start()
        }
    }

    private fun sortAlbums(albums: MutableList<AlbumItem>) {
        if (sortMode == SORT_RANDOM) {
            albums.shuffle(Random(42))
            if (sortDesc) albums.reverse()
            return
        }
        albums.sortWith { first, second ->
            val result = when (sortMode) {
                SORT_NAME -> first.name.compareTo(second.name, ignoreCase = true)
                SORT_PATH -> first.path.compareTo(second.path, ignoreCase = true)
                SORT_SIZE -> first.totalSize.compareTo(second.totalSize)
                SORT_CREATED -> first.firstDate.compareTo(second.firstDate)
                else -> first.latestDate.compareTo(second.latestDate)
            }
            if (sortDesc) -result else result
        }
    }

    private fun isHiddenAlbum(album: AlbumItem): Boolean {
        val path = (album.path.ifEmpty { album.key }).replace('\\', '/')
        if (path.isEmpty()) return false
        val parts = path.split("/")
        return parts.any { it.startsWith(".") || it.equals("Private", true) || it.equals("Hidden", true) }
    }

    private fun matchesMediaFilter(item: MediaItem): Boolean {
        val mime = item.mimeType.lowercase(Locale.US)
        val name = item.name.lowercase(Locale.US)
        if (isGif(mime, name)) return showGifs
        if (isSvg(mime, name)) return showSvgs
        if (isRaw(name)) return showRaw
        if (item.isVideo()) return showVideos
        if (item.isImage()) return showImages || showPortraits
        return false
    }

    private fun isGif(mime: String, name: String): Boolean =
        mime == "image/gif" || name.endsWith(".gif")

    private fun isSvg(mime: String, name: String): Boolean =
        mime == "image/svg+xml" || name.endsWith(".svg")

    private fun isRaw(name: String): Boolean =
        name.endsWith(".dng") ||
            name.endsWith(".raw") ||
            name.endsWith(".cr2") ||
            name.endsWith(".nef") ||
            name.endsWith(".arw") ||
            name.endsWith(".orf") ||
            name.endsWith(".rw2")

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
            ensureFileManagementAccess(false)
            loadAlbums()
        } else {
            emptyView.visibility = View.VISIBLE
            emptyView.text = "Autorize acesso completo a fotos e vídeos para carregar a galeria."
        }
    }

    private fun ensureFileManagementAccess(force: Boolean) {
        if (MediaActions.hasAllFilesAccess(this)) return
        if (!force && prefs.getBoolean(PREF_ALL_FILES_PROMPTED, false)) return
        AlertDialog.Builder(this)
            .setTitle("Permitir gerenciamento de arquivos")
            .setMessage("Para excluir, mover, copiar e criar pastas no celular, ative o acesso total a arquivos para a Galeria.")
            .setPositiveButton("Permitir") { _, _ ->
                prefs.edit().putBoolean(PREF_ALL_FILES_PROMPTED, true).apply()
                MediaActions.requestAllFilesAccess(this)
            }
            .setNegativeButton("Agora não") { _, _ ->
                prefs.edit().putBoolean(PREF_ALL_FILES_PROMPTED, true).apply()
            }
            .show()
    }

    private fun statusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else Ui.dp(this, 24)
    }

    companion object {
        private const val REQ_READ = 10
        private const val PREFS = "gallery_albums"
        private const val PREF_ALL_FILES_PROMPTED = "all_files_prompted"
        private const val SORT_NAME = "name"
        private const val SORT_PATH = "path"
        private const val SORT_SIZE = "size"
        private const val SORT_MODIFIED = "modified"
        private const val SORT_CREATED = "created"
        private const val SORT_RANDOM = "random"
    }
}
