package com.galeria.android

import android.app.AlertDialog
import android.app.RecoverableSecurityException
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.AudioManager
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.format.Formatter
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.exoplayer.ExoPlayer
import androidx.core.view.WindowCompat
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil3.SingletonImageLoader
import coil3.load
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Scale
import com.github.panpf.zoomimage.CoilZoomImageView
import com.github.panpf.zoomimage.view.zoom.OnViewTapListener
import com.github.panpf.zoomimage.view.zoom.OnViewLongPressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * File actions of the viewer: rename, rotate, share, hide, copy, move, delete, restore, wallpaper and PDF.
 */

internal fun DetailActivity.askRenameCurrentImage() {
    val item = currentItem()
    if (!item.isImage()) return
    Ui.showTextInputDialog(
        this,
        title = getString(R.string.msg_rename_image),
        hint = getString(R.string.msg_file_name),
        positiveText = getString(R.string.msg_rename),
        initialValue = item.name
    ) { requestedName ->
        val newName = ViewerMenuRules.normalizedRename(requestedName, item.name)
        when {
            newName == null -> Ui.toast(this, getString(R.string.msg_invalid_name))
            newName == item.name -> Unit
            else -> renameImage(item, newName, requestPermission = true)
        }
    }
}

internal fun DetailActivity.renameImage(item: MediaItem, newName: String, requestPermission: Boolean) {
    if (operations.busy) return
    operations.run({ runCatching { mediaActions.rename(item, newName) } }) { outcome ->
    try {
        val updated = outcome.getOrThrow()
        if (updated) {
            applyRenamedItem(item, newName)
            pendingRenameItem = null
            pendingRenameName = null
            Ui.toast(this, getString(R.string.msg_image_renamed))
        } else if (requestPermission && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            pendingRenameItem = item
            pendingRenameName = newName
            requestRenameWrite(item.uri)
        } else {
            Ui.toast(this, getString(R.string.msg_rename_failed))
        }
    } catch (error: SecurityException) {
        if (!requestPermission) {
            Ui.toast(this, getString(R.string.msg_rename_failed))
            return@run
        }
        pendingRenameItem = item
        pendingRenameName = newName
        requestRenamePermission(item.uri, error)
    }
    }
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
internal fun DetailActivity.requestRenameWrite(uri: Uri) {
    try {
        launchSender(renameWriteLauncher, MediaStore.createWriteRequest(contentResolver, listOf(uri)).intentSender)
    } catch (_: Exception) {
        Ui.toast(this, getString(R.string.msg_rename_permission_failed))
    }
}

internal fun DetailActivity.requestRenamePermission(uri: Uri, error: SecurityException) {
    when {
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is RecoverableSecurityException -> {
            launchSender(renameWriteLauncher, error.userAction.actionIntent.intentSender)
        }
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> requestRenameWrite(uri)
        else -> Ui.toast(this, getString(R.string.msg_rename_permission_failed))
    }
}

internal fun DetailActivity.applyRenamedItem(item: MediaItem, newName: String) {
    val currentRenamed = queueController.replace(item, mediaActions.renamedItem(item, newName))
    if (currentRenamed) title.text = newName
    MediaStoreRepository.invalidateCache()
}

internal fun DetailActivity.shareCurrent() {
    startActivity(Intent.createChooser(mediaActions.shareIntent(currentItem()), getString(R.string.action_share)))
}

internal fun DetailActivity.confirmHideCurrent() {
    Ui.showConfirmationDialog(
        this,
        getString(R.string.msg_hide_file_title),
        getString(R.string.msg_hide_file_message),
        getString(R.string.msg_hide)
    ) { hideCurrent() }
}

internal fun DetailActivity.hideCurrent() {
    if (operations.busy) return
    val item = currentItem()
    pendingDeleteUri = item.uri
    operations.run({
        val copy = mediaActions.copyToHidden(item)
        val result = if (copy == null) MediaActions.RESULT_FAILED else mediaActions.delete(item, DetailActivity.REQ_HIDE_DELETE)
        if (result == MediaActions.RESULT_FAILED) copy?.delete()
        copy to result
    }) { (copy, result) ->
    pendingHiddenCopy = copy
    if (result == MediaActions.RESULT_DONE) {
        Ui.toast(this, getString(R.string.msg_item_hidden))
        removeDeletedItem()
    } else if (result == MediaActions.RESULT_FAILED) {
        pendingDeleteUri = null
        pendingHiddenCopy = null
        requestFileManagementAccess()
    }
    }
}

internal fun DetailActivity.askFolderForCopyOrMove(copy: Boolean, anchor: View) {
    if (operations.busy) return
    val item = currentItem()
    val exposedKeys = intent.getStringArrayListExtra(AlbumTargetRules.EXTRA_EXPOSED_ALBUM_KEYS)?.toList()
    val hiddenKeys = prefs.getStringSet(GalleryPreferences.HIDDEN_FOLDER_KEYS, emptySet()).orEmpty()
    val includeHidden = exposedKeys != null && StorageAccessRules.includeHiddenFilesystem(
        intent.getBooleanExtra("include_hidden_filesystem", false),
        MediaActions.hasAllFilesAccess(this)
    )
    mediaActions.loadTargets(
        exposedKeys,
        hiddenKeys,
        setOfNotNull(item.albumKey, intent.getStringExtra("album_key")),
        includeHidden
    ) { targets ->
        if (operations.busy || isFinishing || !anchor.isAttachedToWindow || currentItem().uri != item.uri) return@loadTargets
        fun sendTo(album: AlbumItem) {
            if (operations.busy) return
            val folder = album.path.ifBlank { album.name }
            if (copy) {
                copyCurrentToFolder(item, folder)
            } else {
                pendingMoveDestinationKey = album.key
                pendingMoveDestinationName = album.name
                moveCurrentToFolder(item, folder)
            }
        }
        // With no other album the panel still opens: "Nova pasta" is the destination then.
        Ui.showAlbumTargets(anchor, getString(if (copy) R.string.msg_copy_to else R.string.msg_move_to), targets,
            onNewFolder = {
                FolderCreationMenu(this, forMove = true) { folder ->
                    if (!isFinishing && currentItem().uri == item.uri) sendTo(MediaActions.newFolderTarget(folder))
                }.show()
            }
        ) { album -> sendTo(album) }
    }
}

internal fun DetailActivity.copyCurrentToFolder(item: MediaItem, folder: String) {
    if (operations.busy) return
    operations.run({ mediaActions.copyToFolder(item, folder) }) { result ->
    if (result == MediaActions.RESULT_DONE) {
        Ui.toast(this, getString(R.string.msg_item_copied))
    } else {
        requestFileManagementAccess()
    }
    }
}

internal fun DetailActivity.moveCurrentToFolder(item: MediaItem, folder: String) {
    if (operations.busy) return
    pendingMoveItem = item
    pendingMoveFolder = folder
    operations.run({
        val sourceFolder = MediaActions.fileFromMediaStore(this, item.uri)?.parentFile
        val result = mediaActions.moveToFolder(item, folder)
        result to MediaOperationNavigation.isEmptyMediaFolder(sourceFolder)
    }) { (result, sourceEmpty) ->
    if (result == MediaActions.RESULT_DONE) {
        Ui.toast(this, getString(R.string.msg_item_moved))
        EmptyFolderCleaner.afterRemoval(this, listOf(item.relativePath))
        completeMovedItem(item, folder, sourceEmpty)
        pendingMoveItem = null
        pendingMoveFolder = null
    } else if (result == MediaActions.RESULT_NEEDS_PERMISSION && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        MediaActions.requestWrite(this, item.uri, DetailActivity.REQ_MOVE_WRITE)
    } else {
        pendingMoveItem = null
        pendingMoveFolder = null
        requestFileManagementAccess()
    }
    }
}

internal fun DetailActivity.setCurrentAsWallpaper() {
    if (currentItem().isVideo()) {
        Ui.toast(this, getString(R.string.msg_images_only))
        return
    }
    val item = currentItem()
    executor.execute {
        try {
            val bitmap = decodeBitmap(item.uri, 2600)
            WallpaperManager.getInstance(this).setBitmap(bitmap)
            runOnUiThread { Ui.toast(this, getString(R.string.msg_wallpaper_set)) }
        } catch (_: Exception) {
            runOnUiThread { Ui.toast(this, getString(R.string.msg_wallpaper_failed)) }
        }
    }
}

internal fun DetailActivity.rotateCurrentImage() {
    if (currentItem().isVideo()) {
        Ui.toast(this, getString(R.string.msg_images_only))
        return
    }
    if (imageRotationRunning || pendingRotateItem != null) return
    if (!ImageRotation.supportsInPlace(currentItem())) {
        Ui.toast(this, getString(R.string.image_rotate_copy_required))
        openImageEditor(rotateClockwise = true)
        return
    }
    pendingRotateItem = currentItem()
    rotateImage(pendingRotateItem!!)
}

internal fun DetailActivity.rotateImage(item: MediaItem, requestPermission: Boolean = true) {
    imageRotationRunning = true
    executor.execute {
        try {
            ImageRotation.clockwise(applicationContext, item)
            MediaContentRevision.changed(applicationContext, item.uri)
            MediaStoreRepository.invalidateCache()
            GalleryCatalogStore.markCatalogDirty(applicationContext)
            // Refresh provider metadata/thumbnails when a real file path is available.
            runCatching {
                MediaActions.fileFromMediaStore(this, item.uri)?.let { file ->
                    android.media.MediaScannerConnection.scanFile(applicationContext,
                        arrayOf(file.absolutePath), arrayOf(item.mimeType)) { _, _ ->
                        MediaStoreRepository.invalidateCache()
                        GalleryCatalogStore.markCatalogDirty(applicationContext)
                    }
                }
            }
            runOnUiThread {
                imageRotationRunning = false
                pendingRotateItem = null
                imageMetadataCache.remove(item.uri.toString())
                imagePreloadJobs.values.forEach { it.cancel() }
                imagePreloadJobs.clear()
                if (!isFinishing && !isDestroyed) {
                    Ui.toast(this, getString(R.string.image_rotate_done))
                    if (currentItem().uri == item.uri) loadCurrentItem()
                    scheduleAdjacentPreload()
                }
            }
        } catch (error: SecurityException) {
            android.util.Log.w("ImageRotation", "Rotação requer permissão de gravação", error)
            runOnUiThread {
                imageRotationRunning = false
                if (!requestPermission || !requestRotationPermission(item, error)) {
                    pendingRotateItem = null
                    Haptics.perform(this, Haptics.Effect.REJECT)
                    Ui.toast(this, getString(R.string.image_rotate_permission_error))
                }
            }
        } catch (error: Exception) {
            android.util.Log.e("ImageRotation", "Falha ao girar imagem", error)
            runOnUiThread {
                imageRotationRunning = false
                pendingRotateItem = null
                Haptics.perform(this, Haptics.Effect.REJECT)
                Ui.toast(this, getString(R.string.image_rotate_error))
            }
        }
    }
}

internal fun DetailActivity.requestRotationPermission(item: MediaItem, error: SecurityException): Boolean {
    if (isFinishing || isDestroyed) return false
    return try {
        val sender = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && error is RecoverableSecurityException) {
            error.userAction.actionIntent.intentSender
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && item.uri.scheme == "content" && item.uri.authority == MediaStore.AUTHORITY) {
            MediaStore.createWriteRequest(contentResolver, listOf(item.uri)).intentSender
        } else return false
        launchSender(rotateWriteLauncher, sender)
        true
    } catch (_: Exception) { false }
}

internal fun DetailActivity.createPdfFromCurrentImage() {
    if (currentItem().isVideo()) {
        Ui.toast(this, getString(R.string.msg_images_only))
        return
    }
    pendingPdfItem = currentItem()
    var name = pendingPdfItem?.name.orEmpty()
    val dot = name.lastIndexOf('.')
    if (dot > 0) name = name.substring(0, dot)
    createPdfLauncher.launch("$name.pdf")
}

internal fun DetailActivity.writePdf(outputUri: Uri, item: MediaItem) {
    executor.execute {
        val document = PdfDocument()
        try {
            val bitmap = decodeBitmap(item.uri, 2400)
            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
            val page = document.startPage(pageInfo)
            val canvas: Canvas = page.canvas
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)
            document.finishPage(page)
            contentResolver.openOutputStream(outputUri).use { output ->
                if (output == null) throw IllegalStateException("output")
                document.writeTo(output)
            }
            runOnUiThread { Ui.toast(this, getString(R.string.msg_pdf_created)) }
        } catch (_: Exception) {
            runOnUiThread { Ui.toast(this, getString(R.string.msg_pdf_failed)) }
        } finally {
            document.close()
        }
    }
}

internal fun DetailActivity.confirmDeleteCurrent() {
    val permanent = TrashPreferences.deletePermanently(this, sourceAlbumKey)
    if (!permanent && GalleryPreferences.flag(this, GalleryPreferences.SKIP_DELETE_CONFIRMATION)) {
        deleteCurrent()
        return
    }
    Ui.showConfirmationDialog(
        this,
        if (permanent) getString(R.string.trash_delete_selected_title) else getString(R.string.move_item_to_trash_title),
        if (permanent) getString(R.string.trash_delete_item_message)
            else getString(R.string.move_item_to_trash_message),
        getString(if (permanent) R.string.action_delete_permanently else R.string.action_move_to_trash)
    ) { deleteCurrent() }
}

internal fun DetailActivity.deleteCurrent() {
    if (operations.busy) return
    val item = currentItem()
    pendingDeleteUri = item.uri
    val permanent = TrashPreferences.deletePermanently(this, sourceAlbumKey)
    pendingDeletePermanent = permanent
    operations.run({ mediaActions.delete(item, DetailActivity.REQ_DELETE, permanent) }) { result ->
    if (result == MediaActions.RESULT_DONE) {
        Ui.toast(this, getString(if (permanent) R.string.album_item_deleted else R.string.item_moved_to_trash))
        EmptyFolderCleaner.afterRemoval(this, listOf(item.relativePath))
        removeDeletedItem()
    } else if (result == MediaActions.RESULT_FAILED) {
        pendingDeleteUri = null
        requestFileManagementAccess()
    }
    }
}

internal fun DetailActivity.requestFileManagementAccess() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !MediaActions.hasAllFilesAccess(this)) {
        Ui.showConfirmationDialog(
            this,
            getString(R.string.msg_full_access_title),
            getString(R.string.msg_full_access_message),
            getString(R.string.msg_allow)
        ) { MediaActions.requestAllFilesAccess(this) }
    } else {
        Ui.toast(this, getString(R.string.msg_operation_failed))
    }
}

internal fun DetailActivity.updateOperationResult(destinationKey: String? = null, destinationName: String? = null) {
    setResult(android.app.Activity.RESULT_OK, Intent().apply {
        putStringArrayListExtra(MediaOperationNavigation.EXTRA_REMOVED_URIS, ArrayList(removedUris))
        putStringArrayListExtra(MediaOperationNavigation.EXTRA_MOVED_URIS, ArrayList(movedUris))
        if (destinationKey != null) {
            putExtra(MediaOperationNavigation.EXTRA_DESTINATION_KEY, destinationKey)
            putExtra(MediaOperationNavigation.EXTRA_DESTINATION_NAME, destinationName)
        }
    })
}

internal fun DetailActivity.completeMovedItem(item: MediaItem, folder: String, sourceEmpty: Boolean) {
    movedUris.add(item.uri.toString())
    val sourceKey = intent.getStringExtra("album_key")
    if (!sourceKey.isNullOrEmpty() && !VirtualAlbumRules.remainsAfterMove(sourceKey) &&
        sourceEmpty) {
        val key = pendingMoveDestinationKey ?: MediaActions.destinationRelativePath(folder, item.isVideo())
        val name = pendingMoveDestinationName ?: key.trimEnd('/').substringAfterLast('/')
        updateOperationResult(key, name)
        if (callingActivity == null) {
            startActivity(MediaOperationNavigation.destinationIntent(this, intent, key, name))
        }
        finish()
    } else {
        updateOperationResult()
        removeDeletedItem(moved = true)
    }
}

internal fun DetailActivity.restoreCurrent() {
    if (operations.busy) return
    val item = currentItem()
    pendingDeleteUri = item.uri
    operations.run({ MediaActions.requestRestore(this, item.uri, DetailActivity.REQ_RESTORE) }) { result ->
    when (result) {
        MediaActions.RESULT_DONE -> {
            Ui.toast(this, getString(R.string.item_restored))
            removeDeletedItem()
        }
        MediaActions.RESULT_FAILED -> pendingDeleteUri = null
    }
    }
}

internal fun DetailActivity.removeDeletedItem(moved: Boolean = false) {
    MediaStoreRepository.invalidateCache()
    GalleryCatalogStore.markCatalogDirty(applicationContext)
    if (!moved) {
        removedUris.add(currentItem().uri.toString())
        updateOperationResult()
    }
    pendingDeleteUri = null
    if (!queueController.removeCurrent()) {
        finish()
        return
    }
    loadCurrentItem()
}

internal fun DetailActivity.onRenameWriteResult(granted: Boolean) {
    val item = pendingRenameItem
    val name = pendingRenameName
    if (granted && item != null && name != null) {
        renameImage(item, name, requestPermission = false)
    } else {
        Ui.toast(this, getString(R.string.msg_rename_cancelled))
    }
    pendingRenameItem = null
    pendingRenameName = null
}

internal fun DetailActivity.onRotateWriteResult(granted: Boolean) {
    val item = pendingRotateItem
    if (granted && item != null) {
        rotateImage(item, requestPermission = false)
    } else {
        pendingRotateItem = null
        Ui.toast(this, getString(R.string.msg_rotation_cancelled))
    }
}
