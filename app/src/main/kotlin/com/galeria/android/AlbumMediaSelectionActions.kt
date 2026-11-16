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
 * Actions on the media selected in an album: share, favorite, delete, restore and move.
 */

internal fun AlbumMediaActivity.shareSelected() {
    val selected = adapter.selectedItems()
    val share = selectionCoordinator.shareIntent(selected) ?: return
    startActivity(Intent.createChooser(share, getString(R.string.action_share)))
    ShareLimit.warnIfLimited(this, selected.size)
}

internal fun AlbumMediaActivity.favoriteSelected() {
    val selected = adapter.selectedItems()
    val added = selectionCoordinator.addToFavorites(selected)
    if (added == 0) return
    Ui.toast(this, resources.getQuantityString(R.plurals.items_added_to_favorites, added, added))
    exitSelectionMode()
}

internal fun AlbumMediaActivity.confirmDeleteSelected() {
    val selected = adapter.selectedItems()
    if (selected.isEmpty()) return
    val permanent = TrashPreferences.deletePermanently(this, albumKey)
    // Skipping the question is only allowed when the media goes to the trash and can come back.
    if (!permanent && GalleryPreferences.flag(this, GalleryPreferences.SKIP_DELETE_CONFIRMATION)) {
        deleteSelected(selected, false)
        return
    }
    Ui.showConfirmationDialog(
        this,
        getString(if (permanent) R.string.trash_delete_selected_title else R.string.album_delete_selected_title),
        if (permanent) {
            getString(
                R.string.trash_delete_selected_message,
                resources.getQuantityString(R.plurals.files_count, selected.size, selected.size)
            )
        } else {
            getString(
                R.string.album_delete_selected_message,
                resources.getQuantityString(R.plurals.files_count, selected.size, selected.size)
            )
        },
        getString(if (permanent) R.string.action_delete_permanently else R.string.action_move_to_trash)
    ) { deleteSelected(selected, permanent) }
}

internal fun AlbumMediaActivity.deleteSelected(selected: List<MediaItem>, permanent: Boolean = false) {
    if (operations.busy) return
    if (AlbumMediaRules.requiresFileManagement(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            MediaActions.hasAllFilesAccess(this)
        )
    ) {
        requestFileManagementAccess()
        return
    }
    pendingDeletePermanent = permanent || !TrashPreferences.isEnabled(this)
    val deletePermanently = pendingDeletePermanent
    operations.run({
        if (deletePermanently) selectionCoordinator.permanentlyDelete(selected, AlbumMediaActivity.REQ_DELETE)
        else selectionCoordinator.delete(selected, AlbumMediaActivity.REQ_DELETE)
    }) { result ->
    EmptyFolderCleaner.afterRemoval(this, result.completedItems.map { it.relativePath })
    completedRemovalUris.addAll(result.completedItems.map { MediaIdentityRules.canonicalKey(it.uri.toString()) })
    adapter.removeCompletedItems(result.completedItems.map { it.uri.toString() })
    updateEmptyState()
    Ui.toast(
        this,
        resources.getQuantityString(
            if (pendingDeletePermanent) R.plurals.items_deleted else R.plurals.items_moved_to_trash,
            result.completed,
            result.completed
        )
    )
    exitSelectionMode()
    if (result.completed > 0) finishIfAlbumEmpty()
    }
}

internal fun AlbumMediaActivity.restoreSelected() {
    if (operations.busy) return
    val selected = adapter.selectedItems()
    if (selected.isEmpty()) return
    operations.run({ selectionCoordinator.restore(selected, AlbumMediaActivity.REQ_RESTORE) }) { result ->
    completedRemovalUris.addAll(result.completedItems.map { MediaIdentityRules.canonicalKey(it.uri.toString()) })
    adapter.removeCompletedItems(result.completedItems.map { it.uri.toString() })
    updateEmptyState()
    Ui.toast(this, resources.getQuantityString(R.plurals.items_restored, result.completed, result.completed))
    exitSelectionMode()
    }
}

internal fun AlbumMediaActivity.askMoveSelected() {
    val selected = adapter.selectedItems()
    if (selected.isEmpty()) return
    val exposedKeys = intent.getStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS)?.toList()
    val hiddenKeys = prefs.getStringSet(GalleryPreferences.HIDDEN_FOLDER_KEYS, emptySet()).orEmpty()
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

internal fun AlbumMediaActivity.moveSelected(selected: List<MediaItem>, destination: AlbumItem) {
    if (operations.busy) return
    if (AlbumMediaRules.requiresFileManagement(
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
            MediaActions.hasAllFilesAccess(this)
        )
    ) {
        requestFileManagementAccess()
        return
    }
    operations.run({ selectionCoordinator.move(selected, destination.path.ifBlank { destination.name }) }) { result ->
    EmptyFolderCleaner.afterRemoval(this, result.completedItems.map { it.relativePath })
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
}

internal fun AlbumMediaActivity.openMoveDestination(key: String, name: String) {
    startActivity(MediaOperationNavigation.destinationIntent(this, intent, key, name))
    finish()
}
