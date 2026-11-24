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
 * Information, map, slideshow and editor entry points of the viewer.
 */

internal fun DetailActivity.openCurrentWithAnotherApp() {
    val item = currentItem()
    val openWithTitle = getString(if (item.isVideo()) R.string.msg_open_video_with else R.string.msg_open_image_with)
    try {
        startActivity(Intent.createChooser(mediaActions.openWithIntent(item), openWithTitle))
    } catch (_: ActivityNotFoundException) {
        Ui.toast(this, getString(R.string.msg_no_compatible_app))
    }
}

internal fun DetailActivity.showCurrentVideoInformation() {
    val item = currentItem()
    val playerDuration = playbackController.durationMs()
    executor.execute {
        val metadata = metadataRepository.readVideo(item.uri, playerDuration)
        val information = metadataFormatter.videoSections(
            item.metadataDescription(),
            metadata,
            metadataRepository.resolveMediaSize(item.uri, item.size)
        )
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            Ui.showMetadataDialog(this, getString(R.string.msg_video_info), information)
        }
    }
}

internal fun DetailActivity.showCurrentImageInformation() {
    val item = currentItem()
    val key = item.uri.toString()
    imageMetadataCache[key]?.let { metadata ->
        showImageInformationDialog(item, metadata)
        return
    }
    executor.execute {
        val metadata = metadataRepository.readImage(item.uri).also {
            imageMetadataCache[key] = it
        }
        runOnUiThread {
            showImageInformationDialog(item, metadata)
        }
    }
}

internal fun DetailActivity.showImageInformationDialog(item: MediaItem, metadata: DetailImageMetadata) {
    if (isFinishing || isDestroyed) return
    Ui.showMetadataDialog(
        this,
        getString(R.string.msg_image_info),
        metadataFormatter.imageSections(
            item.metadataDescription(),
            metadata,
            metadataRepository.resolveMediaSize(item.uri, item.size)
        )
    )
}

internal fun DetailActivity.openCurrentImageOnMap() {
    val item = currentItem()
    executor.execute {
        val key = item.uri.toString()
        val metadata = imageMetadataCache[key] ?: metadataRepository.readImage(item.uri).also {
            imageMetadataCache[key] = it
        }
        val latitude = metadata.latitude
        val longitude = metadata.longitude
        runOnUiThread {
            if (latitude == null || longitude == null) {
                Ui.toast(this, getString(R.string.msg_no_gps))
                return@runOnUiThread
            }
            val location = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude")
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW, location), getString(R.string.msg_show_location)))
            } catch (_: ActivityNotFoundException) {
                Ui.toast(this, getString(R.string.msg_no_maps_app))
            }
        }
    }
}

internal fun DetailActivity.startImagePresentation() {
    val currentUri = currentItem().uri
    val images = mediaQueue.filter { it.isImage() }
    if (images.size < 2) {
        Ui.toast(this, getString(R.string.msg_slideshow_needs_two))
        return
    }
    queueController.retainImages(currentUri)
    presentationMode = true
    restoredShuffleDelayMs = null
    scheduleAdjacentPreload()
    scheduleShuffleAdvance()
    if (hudVisible) toggleHud()
    Ui.toast(this, getString(R.string.msg_slideshow_started))
}

internal fun DetailActivity.showImageEditMenu() {
    EditorUi.editMenu(this, listOf(
        { openImageEditor(ImageEditActivity.MODE_CROP) },
        { rotateCurrentImage() },
        { openImageEditor() }
    ))
}

internal fun DetailActivity.openImageEditor(mode: String = ImageEditActivity.MODE_CUSTOM, rotateClockwise: Boolean = false) {
    if (currentItem().isVideo()) {
        Ui.toast(this, getString(R.string.msg_images_only))
        return
    }
    val item = currentItem()
    confirmPublicEditedCopy(item) {
        startActivity(Intent(this, ImageEditActivity::class.java).apply {
            putExtra(ImageEditActivity.EXTRA_ROTATE_CLOCKWISE, rotateClockwise)
            putExtra("uri", item.uri.toString())
            putExtra("name", item.name)
            putExtra("mime", item.mimeType)
            putExtra(ImageEditActivity.EXTRA_MODE, mode)
        })
    }
}

internal fun DetailActivity.openCurrentEditor() {
    if (!currentItem().isVideo()) {
        openImageEditor()
        return
    }
    val item = currentItem()
    confirmPublicEditedCopy(item) {
        // One editor for every video: a single clip to cut, or the start of a montage.
        startActivity(MediaJoinActivity.intent(this, listOf(item)))
    }
}

internal fun DetailActivity.confirmPublicEditedCopy(item: MediaItem, open: () -> Unit) {
    val hiddenKeys = prefs.getStringSet(GalleryPreferences.HIDDEN_FOLDER_KEYS, emptySet()).orEmpty()
    val markedHidden = HiddenDirectoryMarkers(Environment.getExternalStorageDirectory())
        .containsNomedia(item.relativePath)
    if (!markedHidden && !VirtualAlbumRules.isHiddenMedia(item, hiddenKeys)) {
        open()
        return
    }
    AlertDialog.Builder(this)
        .setTitle(getString(R.string.msg_edit_hidden_title))
        .setMessage(getString(R.string.msg_edit_hidden_message))
        .setNegativeButton(getString(R.string.action_cancel), null)
        .setPositiveButton(getString(R.string.msg_continue)) { _, _ -> open() }
        .show()
}
