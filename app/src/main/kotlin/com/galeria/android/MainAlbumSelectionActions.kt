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
 * Actions on the albums selected on the main screen: share, protect with password, pin, delete and move.
 */

internal fun MainActivity.shareSelectedAlbums() {
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
            startActivity(Intent.createChooser(ShareLimit.intent(uris), getString(R.string.action_share)))
            ShareLimit.warnIfLimited(this, uris.size)
        }
    }
}

/**
 * Protects the selected albums with one password, or removes the protection when they are all
 * protected already (only from albums whose password matches).
 */
internal fun MainActivity.toggleSecureSelectedAlbums() {
    val albums = adapter.selectedAlbums()
    if (albums.isEmpty()) return
    val security = SecurityStore(prefs)
    val name = albums.singleOrNull()?.name ?: resources.getQuantityString(R.plurals.albums_count, albums.size, albums.size)
    if (albums.all { security.isSecureFolder(it.key) }) {
        Ui.showPasswordDialog(this, getString(R.string.security_folder_title, name)) { password ->
            val matching = albums.filter { security.verifyFolderPassword(it.key, password) }
            if (matching.isEmpty()) return@showPasswordDialog getString(R.string.security_wrong_password)
            matching.forEach { security.removeFolder(it.key) }
            Ui.toast(this, getString(R.string.security_folder_unprotected))
            exitSelectionMode(); loadAlbums()
            null
        }
        return
    }
    Ui.showPasswordDialog(
        this,
        getString(R.string.security_folder_create_title, name),
        getString(R.string.security_folder_create_message),
        confirm = true
    ) { password ->
        albums.forEach { security.setFolderPassword(it.key, password); SecuritySession.unlockFolder(it.key) }
        Ui.toast(this, getString(R.string.security_folder_protected))
        exitSelectionMode(); loadAlbums()
        null
    }
}

internal fun MainActivity.togglePinnedSelectedAlbums() {
    val albums = adapter.selectedAlbums()
    if (albums.isEmpty()) return
    val pinned = HashSet(prefs.getStringSet(VirtualAlbumRules.PINNED_ALBUMS_PREF, emptySet()).orEmpty())
    val unpin = albums.all { it.key in pinned }
    if (unpin) pinned.removeAll(albums.map { it.key }) else pinned.addAll(albums.map { it.key })
    prefs.edit().putStringSet(VirtualAlbumRules.PINNED_ALBUMS_PREF, pinned).apply()
    exitSelectionMode()
    loadAlbums()
}

internal fun MainActivity.confirmDeleteSelectedAlbums() {
    val albums = adapter.selectedAlbums()
    if (albums.isEmpty()) return
    val visibleCount = albums.sumOf { it.count }
    val permanent = !TrashPreferences.isEnabled(this)
    if (!permanent && GalleryPreferences.flag(this, GalleryPreferences.SKIP_DELETE_CONFIRMATION)) {
        deleteSelectedAlbums(albums)
        return
    }
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

internal fun MainActivity.deleteSelectedAlbums(albums: List<AlbumItem>) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
        accessCoordinator.ensureFullAccess(true)
        return
    }
    mediaLoader.execute {
        val completed = arrayListOf<MediaItem>()
        for (item in mediaForAlbums(albums)) {
            if (MediaActions.requestDelete(this, item.uri, MainActivity.REQ_BATCH_DELETE) == MediaActions.RESULT_DONE) {
                completed.add(item)
            }
        }
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            val deleted = completed.size
            EmptyFolderCleaner.afterRemoval(this, completed.map { it.relativePath })
            adapter.removeCompletedItems(completed, moved = false)
            Ui.toast(this, resources.getQuantityString(
                if (TrashPreferences.isEnabled(this)) R.plurals.items_moved_to_trash else R.plurals.items_deleted,
                deleted, deleted))
            exitSelectionMode()
            loadAlbums()
        }
    }
}

internal fun MainActivity.askMoveSelectedAlbums() {
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

internal fun MainActivity.moveSelectedAlbums(albums: List<AlbumItem>, destination: AlbumItem) {
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
            EmptyFolderCleaner.afterRemoval(this, result.completedItems.map { it.relativePath })
            Ui.toast(this, resources.getQuantityString(R.plurals.items_moved, moved, moved))
            exitSelectionMode()
            if (albums.any { it.key in result.emptiedAlbumKeys }) {
                startActivity(MediaOperationNavigation.destinationIntent(this, intent, destination.key, destination.name))
            }
            if (moved > 0) {
                showRefreshing()
                loadAlbums()
            } else {
                loadAlbums()
            }
        }
    }
}
