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

/** Options of the album screen, split between the bottom bar and the panel that replaced the three-dot menu. */
internal object AlbumBrowseLayout {
    val defaultBar = listOf("sort", "organize", "shuffle", "settings")
    val defaultOrder = defaultBar + listOf(
        "filter", "group", "view_mode", "create_folder", "choose_cover", "automatic_cover", "trash_hidden",
        "spacing", "cinema", "theme"
    )
}

internal fun AlbumMediaActivity.albumBrowseLayoutStore() = BrowseLayoutStore(
    prefs, "album", AlbumBrowseLayout.defaultBar, AlbumBrowseLayout.defaultOrder
)

/** The options this album offers right now; the trash and virtual albums leave some out. */
internal fun AlbumMediaActivity.albumBrowseActions(): List<BrowseAction> {
    val inTrash = albumKey == VirtualAlbumRules.TRASH_KEY
    val hasManualCover = prefs.contains(AlbumCoverRules.preferenceKey(albumKey ?: "all_media"))
    val showHiddenTrash = GalleryPreferences.hiddenFlag(this, VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF)
    return buildList {
        add(BrowseAction("sort", R.drawable.ic_sort, getString(R.string.browse_sort), getString(R.string.action_sort_by)) {
            showMediaSortDialog()
        })
        add(BrowseAction("organize", R.drawable.ic_organize, getString(R.string.action_organize)) {
            startActivity(OrganizerActivity.intent(this@albumBrowseActions,
                intent.getStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS),
                shouldIncludeHiddenFilesystem(), albumKey))
        })
        // Trashed media does not offer random playback.
        add(BrowseAction("shuffle", R.drawable.ic_shuffle, getString(R.string.album_random),
            getString(R.string.action_random_playback), enabled = !inTrash) { startRandomPlayback() })
        add(BrowseAction("settings", R.drawable.ic_settings, getString(R.string.action_settings)) {
            startActivity(Intent(this@albumBrowseActions, SettingsActivity::class.java))
        })
        add(BrowseAction("filter", R.drawable.ic_filter, getString(R.string.action_filter_media)) { showMediaFilterDialog() })
        add(BrowseAction("group", R.drawable.ic_group, getString(R.string.album_group_by)) { showGroupDialog() })
        add(BrowseAction("view_mode", R.drawable.ic_view_mode, getString(R.string.album_view_mode)) { showViewModeDialog() })
        if (!inTrash) {
            add(BrowseAction("create_folder", R.drawable.ic_folder_add, getString(R.string.action_create_folder)) {
                showCreateFolderDialog()
            })
            add(BrowseAction("choose_cover", R.drawable.ic_cover, getString(R.string.album_choose_cover)) {
                choosingAlbumCover = true
                searchInput.setText("")
                searchInput.clearFocus()
                hideKeyboard()
                searchInput.isEnabled = false
                updateSearchPresentation()
                Ui.toast(this@albumBrowseActions, getString(R.string.album_choose_cover_hint))
            })
        }
        if (!inTrash && hasManualCover) {
            add(BrowseAction("automatic_cover", R.drawable.ic_cover_auto, getString(R.string.album_automatic_cover)) {
                prefs.edit().remove(AlbumCoverRules.preferenceKey(albumKey ?: "all_media")).apply()
                Ui.toast(this@albumBrowseActions, getString(R.string.album_automatic_cover_saved))
                refreshAlbumBrowseBar()
            })
        }
        if (inTrash) {
            add(BrowseAction("trash_hidden", R.drawable.ic_trash_eye,
                getString(if (showHiddenTrash) R.string.trash_hide_hidden else R.string.trash_show_hidden)) {
                val apply = {
                    prefs.edit().putBoolean(VirtualAlbumRules.SHOW_HIDDEN_TRASH_PREF, !showHiddenTrash).apply()
                    loadMedia(true)
                    refreshAlbumBrowseBar()
                }
                if (!showHiddenTrash) SecurityGate.requireHiddenAccess(this@albumBrowseActions, action = apply) else apply()
            })
        }
        add(BrowseAction("spacing", R.drawable.ic_spacing, getString(R.string.album_grid_spacing)) { showSpacingDialog() })
        if (CinemaModeRules.supportsAlbum(albumKey)) {
            add(BrowseAction("cinema", R.drawable.ic_movie, getString(R.string.album_cinema_mode),
                selected = cinemaPreferences.isEnabled(albumKey)) {
                toggleAlbumCinemaMode()
                refreshAlbumBrowseBar()
            })
        }
        add(BrowseAction("theme", R.drawable.ic_palette, getString(R.string.action_theme)) {
            startActivity(Intent(this@albumBrowseActions, SettingsActivity::class.java)
                .putExtra(SettingsActivity.EXTRA_OPEN_THEME, true))
        })
    }
}

internal fun AlbumMediaActivity.refreshAlbumBrowseBar() {
    val actions = albumBrowseActions().associateBy { it.id }
    browseBar.setActions(albumBrowseLayoutStore().load().bar.mapNotNull(actions::get))
}

internal fun AlbumMediaActivity.openAlbumBrowsePanel() {
    val panel = browsePanel ?: BrowseActionPanel(this, browseBar, albumBrowseLayoutStore(), ::albumBrowseActions) {
        refreshAlbumBrowseBar()
    }.also { browsePanel = it }
    panel.show()
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
