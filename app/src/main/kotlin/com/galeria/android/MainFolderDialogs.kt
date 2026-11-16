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

/**
 * Dialogs of the main screen: sorting, media filter, album spacing, folder layout and show/hide folders.
 */

internal fun MainActivity.showSortDialog() {
    val labels = resources.getStringArray(R.array.main_sort_labels)
    val modes = arrayOf(MainActivity.SORT_NAME, MainActivity.SORT_PATH, MainActivity.SORT_SIZE, MainActivity.SORT_MODIFIED, MainActivity.SORT_CREATED, MainActivity.SORT_RANDOM)
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

internal fun MainActivity.saveSorting() {
    prefs.edit()
        .putString(GalleryPreferences.SORT_MODE, sortMode)
        .putBoolean(GalleryPreferences.SORT_DESC, sortDesc)
        .apply()
}

internal fun MainActivity.showMediaFilterDialog() {
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
            .putBoolean(GalleryPreferences.FILTER_IMAGES, showImages)
            .putBoolean(GalleryPreferences.FILTER_VIDEOS, showVideos)
            .putBoolean(GalleryPreferences.FILTER_GIFS, showGifs)
            .putBoolean(GalleryPreferences.FILTER_RAW, showRaw)
            .putBoolean(GalleryPreferences.FILTER_SVGS, showSvgs)
            .putBoolean(GalleryPreferences.FILTER_PORTRAITS, showPortraits)
            .apply()
        loadAlbums()
    }
}

internal fun MainActivity.showAlbumSpacingDialog() {
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.menuSurface(this@showAlbumSpacingDialog), 16, this@showAlbumSpacingDialog, Ui.menuBorder(this@showAlbumSpacingDialog), 1)
        clipToOutline = true
        Ui.setPadding(this, 24, 20, 24, 12)
    }
    panel.addView(TextView(this).apply {
        setText(R.string.main_album_spacing)
        textSize = 18f
        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        setTextColor(Ui.menuText(this@showAlbumSpacingDialog))
    })
    panel.addView(TextView(this).apply {
        setText(R.string.main_album_spacing_hint)
        textSize = 13f
        setTextColor(Ui.menuText(this@showAlbumSpacingDialog))
        alpha = 0.78f
        Ui.setPadding(this, 0, 6, 0, 12)
    })
    panel.addView(SeekBar(this).apply {
        tag = "album_spacing_slider"
        contentDescription = getString(R.string.main_album_spacing)
        max = 16
        progress = max - albumSpacingDp
        progressTintList = android.content.res.ColorStateList.valueOf(Ui.accent(this@showAlbumSpacingDialog))
        thumbTintList = progressTintList
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.muted(this@showAlbumSpacingDialog))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                albumSpacingDp = 16 - progress
                adapter.setSpacing(albumSpacingDp)
                prefs.edit().putInt(GalleryPreferences.ALBUM_SPACING_DP, albumSpacingDp).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) {
                adapter.setCoverSize(albumCoverSizePx())
            }
        })
    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)))
    val dialog = AlertDialog.Builder(this).setView(panel).create()
    dialog.setOnDismissListener { adapter.setCoverSize(albumCoverSizePx()) }
    Ui.showSidePanel(dialog)
}

internal fun MainActivity.showFolderOrganizationDialog() {
    val labels = resources.getStringArray(R.array.folder_column_labels)
    val checked = max(0, min(4, currentColumnCount - 2))
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

internal fun MainActivity.showFolderVisibilityDialog() {
    val currentAlbums = adapter.visibleAlbumsSnapshot().filter { it.key != "all_media" }
    rememberVisibleFolderKeys(currentAlbums)
    // Open before any database/filesystem recount. The updater belongs to this
    // dialog and ignores deliveries after dismissal or replacement.
    val updateRemembered = showFolderVisibilityDialog(currentAlbums)
    val previouslyVisibleKeys = HashSet(
        prefs.getStringSet(MainActivity.PREF_EVER_VISIBLE_FOLDER_KEYS, HashSet()) ?: HashSet()
    )
    val hiddenKeys = HashSet(prefs.getStringSet(GalleryPreferences.HIDDEN_FOLDER_KEYS, HashSet()) ?: HashSet())
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
            prefs.edit().putStringSet(MainActivity.PREF_EVER_VISIBLE_FOLDER_KEYS, knownKeys).apply()
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

internal fun MainActivity.showFolderVisibilityDialog(albums: List<AlbumItem>): (List<AlbumItem>) -> Unit {
    automaticallyHiddenKeys = albums.filter { it.naturallyHidden }.mapTo(HashSet()) { it.key }
    val hiddenKeys = HashSet(prefs.getStringSet(GalleryPreferences.HIDDEN_FOLDER_KEYS, HashSet()) ?: HashSet())
    val pinnedKeys = HashSet(prefs.getStringSet(MainActivity.PREF_PINNED_HIDDEN_FOLDER_KEYS, HashSet()) ?: HashSet())
    val everVisibleKeys = HashSet(
        prefs.getStringSet(MainActivity.PREF_EVER_VISIBLE_FOLDER_KEYS, HashSet()) ?: HashSet()
    )
    val temporaryKeys = TemporaryAlbumVisibility.activeKeys()
    everVisibleKeys.addAll(albums.map { it.key }.filterNot {
        VirtualAlbumRules.isVirtual(it) || it in temporaryKeys
    })
    val mutableAlbums = albums.filterNot { VirtualAlbumRules.isVirtual(it.key) }.toMutableList()
    val checkedKeys = HashSet<String>()
    lateinit var dialog: AlertDialog
    val dialogBg = Ui.menuSurface(this)
    val dialogRow = Ui.menuOptionSurface(this)
    val dialogElevated = Ui.menuElevatedSurface(this)
    val dialogHighlight = Ui.menuHighlightSurface(this)
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
            .putStringSet(MainActivity.PREF_PINNED_HIDDEN_FOLDER_KEYS, HashSet(pinnedKeys))
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
            val row = (convertView as? LinearLayout) ?: LinearLayout(this@showFolderVisibilityDialog).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = Ui.dp(this@showFolderVisibilityDialog, 58)
                setPadding(
                    Ui.dp(this@showFolderVisibilityDialog, 18),
                    Ui.dp(this@showFolderVisibilityDialog, 8),
                    Ui.dp(this@showFolderVisibilityDialog, 12),
                    Ui.dp(this@showFolderVisibilityDialog, 8)
                )
                addView(
                    TextView(this@showFolderVisibilityDialog).apply {
                        tag = "label"
                        textSize = 16f
                        setTextColor(dialogText)
                        gravity = Gravity.CENTER_VERTICAL
                    },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                addView(
                    ImageButton(this@showFolderVisibilityDialog).apply {
                        tag = "pin"
                        setImageResource(R.drawable.ic_pin)
                        setBackgroundColor(Color.TRANSPARENT)
                        scaleType = ImageView.ScaleType.CENTER
                        contentDescription = getString(R.string.main_pin_hidden)
                        setPadding(
                            Ui.dp(this@showFolderVisibilityDialog, 8),
                            Ui.dp(this@showFolderVisibilityDialog, 8),
                            Ui.dp(this@showFolderVisibilityDialog, 8),
                            Ui.dp(this@showFolderVisibilityDialog, 8)
                        )
                    },
                    LinearLayout.LayoutParams(Ui.dp(this@showFolderVisibilityDialog, 44), Ui.dp(this@showFolderVisibilityDialog, 44))
                )
                addView(
                    ImageButton(this@showFolderVisibilityDialog).apply {
                        tag = "eye"
                        setImageResource(R.drawable.ic_eye_off)
                        setBackgroundColor(Color.TRANSPARENT)
                        scaleType = ImageView.ScaleType.CENTER
                        contentDescription = getString(R.string.main_reveal_hidden_temporarily)
                        setPadding(
                            Ui.dp(this@showFolderVisibilityDialog, 8),
                            Ui.dp(this@showFolderVisibilityDialog, 8),
                            Ui.dp(this@showFolderVisibilityDialog, 8),
                            Ui.dp(this@showFolderVisibilityDialog, 8)
                        )
                    },
                    LinearLayout.LayoutParams(Ui.dp(this@showFolderVisibilityDialog, 44), Ui.dp(this@showFolderVisibilityDialog, 44))
                )
                addView(
                    CheckBox(this@showFolderVisibilityDialog).apply {
                        tag = "check"
                        buttonTintList = android.content.res.ColorStateList.valueOf(dialogText)
                        isClickable = false
                        isFocusable = false
                        gravity = Gravity.CENTER
                    },
                    LinearLayout.LayoutParams(Ui.dp(this@showFolderVisibilityDialog, 44), Ui.dp(this@showFolderVisibilityDialog, 44))
                )
            }
            val album = getItem(position)
            val label = row.findViewWithTag<TextView>("label")
            val pin = row.findViewWithTag<ImageButton>("pin")
            val eye = row.findViewWithTag<ImageButton>("eye")
            val check = row.findViewWithTag<CheckBox>("check")
            val pinned = pinnedKeys.contains(album.key)
            label.text = SpannableString(getString(R.string.main_album_count_label, album.name, album.count)).apply {
                setSpan(RelativeSizeSpan(0.78f), album.name.length.coerceAtMost(length), length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
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
                    !MediaActions.hasAllFilesAccess(this@showFolderVisibilityDialog)) {
                    Ui.toast(this@showFolderVisibilityDialog, getString(R.string.access_hidden_folders_required))
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
            val rowTone = if (pinned || temporarilyRevealed) dialogElevated else dialogRow
            row.setBackgroundColor(rowTone)
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
        observeVisibilityDialogFolders(mutableAlbums)
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
                    if (!isFinishing && !isDestroyed && visibilityDialogAdapter === listAdapter) {
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
            .putStringSet(GalleryPreferences.HIDDEN_FOLDER_KEYS, nextHidden)
            .putStringSet(MainActivity.PREF_EVER_VISIBLE_FOLDER_KEYS, HashSet(everVisibleKeys))
            .putBoolean(GalleryPreferences.SHOW_HIDDEN_FOLDERS, showHiddenFolders)
            .apply()
        loadAlbums()
        scheduleRevealExpiry()
        dialog.dismiss()
    }

    fun dialogButton(text: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(dialogText)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            minimumHeight = Ui.dp(this@showFolderVisibilityDialog, 50)
            isClickable = true
            isFocusable = true
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf((dialogText and 0x00FFFFFF) or 0x18000000),
                Ui.rounded(dialogElevated, 10, this@showFolderVisibilityDialog, Ui.menuBorder(this@showFolderVisibilityDialog), 1),
                Ui.rounded(Color.WHITE, 10, this@showFolderVisibilityDialog)
            )
            setPadding(
                Ui.dp(this@showFolderVisibilityDialog, 14),
                Ui.dp(this@showFolderVisibilityDialog, 8),
                Ui.dp(this@showFolderVisibilityDialog, 14),
                Ui.dp(this@showFolderVisibilityDialog, 8)
            )
            setOnClickListener {
                Haptics.perform(this, Haptics.Effect.TAP)
                onClick()
            }
        }

    val content = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(
            Ui.dp(this@showFolderVisibilityDialog, 22),
            Ui.dp(this@showFolderVisibilityDialog, 16),
            Ui.dp(this@showFolderVisibilityDialog, 22),
            Ui.dp(this@showFolderVisibilityDialog, 52)
        )
        addView(
            TextView(this@showFolderVisibilityDialog).apply {
                setText(R.string.main_folder_visibility)
                textSize = 18f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(dialogText)
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        addView(
            TextView(this@showFolderVisibilityDialog).apply {
                setText(R.string.main_hidden_dialog_help)
                textSize = 14f
                setTextColor(dialogText)
                alpha = 0.78f
                setPadding(0, Ui.dp(this@showFolderVisibilityDialog, 5), 0, Ui.dp(this@showFolderVisibilityDialog, 6))
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        addView(
            LinearLayout(this@showFolderVisibilityDialog).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(
                    LinearLayout(this@showFolderVisibilityDialog).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        isClickable = true
                        setOnClickListener { toggleShowHiddenSelection() }
                        val checkbox = CheckBox(this@showFolderVisibilityDialog).apply {
                            buttonTintList = android.content.res.ColorStateList.valueOf(dialogText)
                            isClickable = false
                            isFocusable = false
                        }
                        showHiddenCheck = checkbox
                        val label = TextView(this@showFolderVisibilityDialog).apply {
                            setText(R.string.main_show_hidden)
                            textSize = 15f
                            setTextColor(dialogText)
                            setPadding(Ui.dp(this@showFolderVisibilityDialog, 8), 0, 0, 0)
                        }
                        showHiddenLabel = label
                        addView(checkbox)
                        addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    },
                    LinearLayout.LayoutParams(0, Ui.dp(this@showFolderVisibilityDialog, 48), 1f)
                )
                addView(
                    FrameLayout(this@showFolderVisibilityDialog).apply {
                      addView(dialogButton(getString(R.string.main_load_hidden)) { loadHiddenAlbums() }.apply {
                        loadHiddenButton = this
                        textSize = 14f
                        setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                        gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        background = android.graphics.drawable.RippleDrawable(
                            android.content.res.ColorStateList.valueOf((dialogText and 0x00FFFFFF) or 0x18000000),
                            null,
                            Ui.rounded(Color.WHITE, 10, this@showFolderVisibilityDialog)
                        )
                        setPadding(
                            Ui.dp(this@showFolderVisibilityDialog, 8),
                            Ui.dp(this@showFolderVisibilityDialog, 8),
                            Ui.dp(this@showFolderVisibilityDialog, 8),
                            Ui.dp(this@showFolderVisibilityDialog, 8)
                        )
                      }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
                      addView(LoadingIndicatorView(this@showFolderVisibilityDialog, dialogText).apply {
                          loadHiddenIndicator = this
                          visibility = View.GONE
                      }, FrameLayout.LayoutParams(Ui.dp(this@showFolderVisibilityDialog, 32), Ui.dp(this@showFolderVisibilityDialog, 32), Gravity.CENTER))
                    },
                    LinearLayout.LayoutParams(0, Ui.dp(this@showFolderVisibilityDialog, 48), 1f)
                )
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        addView(refresher, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    val buttonBar = ClickFeedbackActionLayout(this).apply {
        tag = "hidden_dialog_confirm"
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        contentDescription = getString(R.string.action_ok)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf((dialogText and 0x00FFFFFF) or 0x18000000),
            Ui.rounded(dialogHighlight, 0, this@showFolderVisibilityDialog),
            Color.WHITE.toDrawable()
        )
        setOnClickListener {
            isEnabled = false
            // Let the shared, momentary click pulse finish before closing.
            postDelayed({ if (dialog.isShowing && !isFinishing) applyFolderVisibility() }, 180L)
        }
        addView(
            TextView(this@showFolderVisibilityDialog).apply {
                setText(R.string.action_ok)
                textSize = 14f
                setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                setTextColor(dialogText)
                gravity = Gravity.CENTER
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )
    }
    val panel = FrameLayout(this).apply {
        background = Ui.rounded(dialogBg, 16, this@showFolderVisibilityDialog, Ui.menuBorder(this@showFolderVisibilityDialog), 1)
        clipToOutline = true
        addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(
            buttonBar,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this@showFolderVisibilityDialog, 48), Gravity.BOTTOM)
        )
    }

    dialog = AlertDialog.Builder(this)
        .setView(panel)
        .create()
    dialog.setOnDismissListener {
        visibilityDialogAdapter = null
        refreshVisibilityDialogCounts = null
        mergeVisibleAlbumsIntoDialog = null
        visibilityFolderObservers.values.forEach { it.stopWatching() }
        visibilityFolderObservers.clear()
        if (isHiddenPanelHost() && !isChangingConfigurations && !isFinishing) finish()
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

/** Only watch known hidden folders while their dialog is open. MediaStore
 * does not report direct writes/moves under .nomedia. Never scan the device. */
internal fun MainActivity.observeVisibilityDialogFolders(albums: List<AlbumItem>) {
    if (isFinishing || isDestroyed || visibilityDialogAdapter == null) return
    val wanted = if (MediaActions.hasAllFilesAccess(this)) albums.asSequence()
        .filter { it.requiresFilesystem || AlbumRules.isHidden(it.path, it.key) }
        .map { it.key }.filter(MediaStoreRepository::isPhysicalAlbum).toSet() else emptySet()
    visibilityFolderObservers.keys.filterNot { it in wanted }.forEach {
        visibilityFolderObservers.remove(it)?.stopWatching()
    }
    val root = Environment.getExternalStorageDirectory()
    for (key in wanted) {
        if (key in visibilityFolderObservers) continue
        val directory = java.io.File(root, key)
        if (!runCatching { directory.canonicalPath.startsWith(root.canonicalPath + java.io.File.separator) }
                .getOrDefault(false)) continue
        val observer = object : android.os.FileObserver(directory.absolutePath,
            CREATE or DELETE or MOVED_FROM or MOVED_TO or CLOSE_WRITE or DELETE_SELF or MOVE_SELF) {
            override fun onEvent(event: Int, path: String?) {
                mediaRefreshHandler.post {
                    if (isFinishing || isDestroyed || !mainScreenResumed || visibilityDialogAdapter == null) return@post
                    mediaRefreshHandler.removeCallbacks(visibilityCountRefreshRunnable)
                    mediaRefreshHandler.postDelayed(visibilityCountRefreshRunnable, 180L)
                }
            }
        }
        observer.startWatching()
        visibilityFolderObservers[key] = observer
    }
}

internal fun MainActivity.animateHiddenControl(view: View) {
    view.animate().cancel()
    view.scaleX = 0.76f
    view.scaleY = 0.76f
    view.animate().scaleX(1f).scaleY(1f).setDuration(190)
        .setInterpolator(OvershootInterpolator(1.5f)).start()
}
