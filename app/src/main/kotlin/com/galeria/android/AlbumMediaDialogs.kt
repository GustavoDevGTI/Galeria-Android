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

/**
 * Menu and dialogs of the album screen: folder menu, cinema mode, filters, grouping, view mode, new folder, spacing and sorting.
 */

internal fun AlbumMediaActivity.showFolderMenu(anchor: View) {
    val filter = getString(R.string.action_filter_media)
    val viewMode = getString(R.string.album_view_mode)
    val group = getString(R.string.album_group_by)
    val createFolder = getString(R.string.action_create_folder)
    val random = getString(R.string.album_random)
    val spacing = getString(R.string.album_grid_spacing)
    val cinemaMode = getString(R.string.album_cinema_mode)
    val trashVisibility = getString(
        if (GalleryPreferences.hiddenFlag(this, VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF)) R.string.trash_hide_hidden
        else R.string.trash_show_hidden
    )
    val chooseCover = getString(R.string.album_choose_cover)
    val automaticCover = getString(R.string.album_automatic_cover)
    val hasManualCover = prefs.contains(AlbumCoverRules.preferenceKey(albumKey ?: "all_media"))
    val options = buildList {
        add(filter)
        addAll(listOf(group, viewMode))
        if (albumKey != VirtualAlbumRules.TRASH_KEY) addAll(listOf(createFolder, random))
        if (albumKey != VirtualAlbumRules.TRASH_KEY) add(chooseCover)
        if (albumKey != VirtualAlbumRules.TRASH_KEY && hasManualCover) add(automaticCover)
        if (albumKey == VirtualAlbumRules.TRASH_KEY) add(trashVisibility)
        add(spacing)
        if (CinemaModeRules.supportsAlbum(albumKey)) add(cinemaMode)
    }
    Ui.showPopupOptions(
        anchor,
        options,
        selectedItems = if (cinemaPreferences.isEnabled(albumKey)) setOf(cinemaMode) else emptySet()
    ) { selected ->
        when (selected) {
            filter -> showMediaFilterDialog()
            viewMode -> showViewModeDialog()
            group -> showGroupDialog()
            createFolder -> showCreateFolderDialog()
            random -> startRandomPlayback()
            chooseCover -> {
                choosingAlbumCover = true
                searchInput.setText("")
                searchInput.clearFocus()
                hideKeyboard()
                searchInput.isEnabled = false
                updateSearchPresentation()
                Ui.toast(this, getString(R.string.album_choose_cover_hint))
            }
            automaticCover -> {
                prefs.edit().remove(AlbumCoverRules.preferenceKey(albumKey ?: "all_media")).apply()
                Ui.toast(this, getString(R.string.album_automatic_cover_saved))
            }
            trashVisibility -> {
                val showHidden = !GalleryPreferences.hiddenFlag(this, VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF)
                val apply = {
                    prefs.edit().putBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, showHidden).apply()
                    loadMedia(true)
                }
                if (showHidden) SecurityGate.requireHiddenAccess(this, action = apply) else apply()
            }
            spacing -> showSpacingDialog()
            cinemaMode -> toggleAlbumCinemaMode()
        }
    }
}

internal fun AlbumMediaActivity.toggleAlbumCinemaMode() {
    val enabled = cinemaPreferences.toggle(albumKey)
    Ui.toast(
        this,
        getString(if (enabled) R.string.album_cinema_mode_enabled else R.string.album_cinema_mode_disabled)
    )
}

internal fun AlbumMediaActivity.showMediaFilterDialog() {
    val labels = resources.getStringArray(R.array.album_media_filter_labels)
    val checked = booleanArrayOf(showImages, showVideos, showGifs, showRaw, showSvgs)
    Ui.showMultiChoiceDialog(this, getString(R.string.action_filter_media), labels, checked) { selected ->
        showImages = selected[0]
        showVideos = selected[1]
        showGifs = selected[2]
        showRaw = selected[3]
        showSvgs = selected[4]
        prefs.edit()
            .putBoolean(optionKey(GalleryPreferences.FILTER_IMAGES), showImages)
            .putBoolean(optionKey(GalleryPreferences.FILTER_VIDEOS), showVideos)
            .putBoolean(optionKey(GalleryPreferences.FILTER_GIFS), showGifs)
            .putBoolean(optionKey(GalleryPreferences.FILTER_RAW), showRaw)
            .putBoolean(optionKey(GalleryPreferences.ALBUM_FILTER_SVG), showSvgs)
            .apply()
        loadMedia(true)
    }
}

internal fun AlbumMediaActivity.showGroupDialog() {
    val labels = resources.getStringArray(R.array.album_group_labels)
    val values = arrayOf(AlbumMediaActivity.GROUP_NONE, AlbumMediaActivity.GROUP_TYPE, AlbumMediaActivity.GROUP_EXTENSION, AlbumMediaActivity.GROUP_DAY, AlbumMediaActivity.GROUP_MONTH)
    var choice = values.indexOf(groupMode).takeIf { it >= 0 } ?: 0
    Ui.showChoiceDialog(this, getString(R.string.album_group_by), labels, choice) { which ->
        choice = which
        groupMode = values[choice]
        prefs.edit().putString(optionKey("group_mode"), groupMode).apply()
        loadMedia(true)
    }
}

internal fun AlbumMediaActivity.showViewModeDialog() {
    val labels = resources.getStringArray(R.array.album_view_mode_labels)
    var choice = if (listMode) 1 else 0
    Ui.showChoiceDialog(this, getString(R.string.album_view_mode), labels, choice) { which ->
        choice = which
        listMode = choice == 1
        prefs.edit().putBoolean(optionKey("list_mode"), listMode).apply()
        applyViewMode()
    }
}

internal fun AlbumMediaActivity.showCreateFolderDialog() {
    Ui.showTextInputDialog(
        this,
        getString(R.string.action_create_folder),
        getString(R.string.field_title),
        message = folderDisplayPath()
    ) { name ->
        createFolder(name)
    }
}

internal fun AlbumMediaActivity.showSpacingDialog() {
    val panel = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rounded(Ui.menuSurface(this@showSpacingDialog), 16, this@showSpacingDialog, Ui.menuBorder(this@showSpacingDialog), 1)
        clipToOutline = true
        setPadding(
            Ui.dp(this@showSpacingDialog, 24),
            Ui.dp(this@showSpacingDialog, 20),
            Ui.dp(this@showSpacingDialog, 24),
            Ui.dp(this@showSpacingDialog, 12)
        )
    }
    panel.addView(
        TextView(this).apply {
            setText(R.string.album_grid_spacing)
            textSize = 18f
            setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
            setTextColor(Ui.menuText(this@showSpacingDialog))
        },
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    )
    val hint = TextView(this).apply {
        setText(R.string.album_grid_spacing_hint)
        textSize = 13f
        setTextColor(Ui.menuText(this@showSpacingDialog))
        alpha = 0.78f
        gravity = Gravity.START
        setPadding(0, Ui.dp(this@showSpacingDialog, 6), 0, Ui.dp(this@showSpacingDialog, 12))
    }
    panel.addView(hint, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val seekBar = SeekBar(this).apply {
        max = AlbumMediaActivity.MAX_GRID_SPACING_DP
        progress = AlbumMediaActivity.MAX_GRID_SPACING_DP - gridSpacingDp
        progressTintList = android.content.res.ColorStateList.valueOf(Ui.accent(this@showSpacingDialog))
        thumbTintList = android.content.res.ColorStateList.valueOf(Ui.accent(this@showSpacingDialog))
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Ui.muted(this@showSpacingDialog))
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                gridSpacingDp = AlbumMediaActivity.MAX_GRID_SPACING_DP - progress
                applyGridSpacing()
                prefs.edit().putInt(spacingKey(), gridSpacingDp).apply()
            }
            override fun onStartTrackingTouch(bar: SeekBar?) = Unit
            override fun onStopTrackingTouch(bar: SeekBar?) = Unit
        })
    }
    panel.addView(seekBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 48)))
    val dialog = AlertDialog.Builder(this).setView(panel).create()
    Ui.showSidePanel(dialog)
}

internal fun AlbumMediaActivity.showMediaSortDialog() {
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
    Ui.showSortChoiceDialog(
        this,
        getString(R.string.action_sort_by),
        labels,
        values,
        selected,
        mediaSortDescending,
        message = getString(R.string.album_sort_group_hint)
    ) { which, descending ->
        mediaSortMode = values[which]
        mediaSortDescending = descending
        saveMediaSortOptions()
        loadMedia(true)
    }
}

internal fun AlbumMediaActivity.saveMediaSortOptions() {
    prefs.edit()
        .putString(optionKey(GalleryPreferences.SORT_MODE), mediaSortMode)
        .putBoolean(optionKey(GalleryPreferences.SORT_DESC), mediaSortDescending)
        .apply()
}
