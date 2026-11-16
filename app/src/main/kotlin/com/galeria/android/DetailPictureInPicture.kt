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
 * Floating thumbnail (picture-in-picture) of the viewer: when to enter, its buttons and the video shape.
 */

internal fun DetailActivity.isInPictureInPictureModeCompat(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode

// "Playing" here means the user asked the video to play: a short looping video passes through
// brief ended/buffering moments where isPlaying is false, and back must still go to the thumbnail.
internal fun DetailActivity.videoActiveForPictureInPicture(): Boolean {
    val player = playbackController.player() ?: return false
    return player.playWhenReady && player.playbackState != androidx.media3.common.Player.STATE_ENDED
}

internal fun DetailActivity.pictureInPictureSupported(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

internal fun DetailActivity.enterPictureInPictureIfPlaying(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    val allowed = DetailPictureInPictureRules.shouldEnter(
        supported = pictureInPictureSupported(),
        isVideo = mediaQueue.getOrNull(currentIndex)?.isVideo() == true,
        playing = videoActiveForPictureInPicture(),
        alreadyInPictureInPicture = isInPictureInPictureModeCompat(),
        finishing = isFinishing
    )
    if (!allowed) return false
    closeOcrSelection()
    speedPopup?.dismiss()
    cancelPendingSingleTap()
    // Hide the bars before shrinking so they never appear inside the thumbnail or its animation.
    pictureInPictureShownPlaying = true
    Haptics.perform(this, Haptics.Effect.THRESHOLD)
    hideViewerChromeForPictureInPicture()
    val entered = try {
        enterPictureInPictureMode(pictureInPictureParams())
    } catch (_: IllegalStateException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }
    if (entered) registerPictureInPictureControls() else restoreViewerChromeAfterPictureInPicture()
    return entered
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
internal fun DetailActivity.pictureInPictureParams(): android.app.PictureInPictureParams {
    val (shownWidth, shownHeight) = videoDisplaySize()
    val (width, height) = DetailPictureInPictureRules.aspectRatio(shownWidth, shownHeight)
    pictureInPictureShownRatio = width to height
    val playing = videoActiveForPictureInPicture()
    val label = getString(if (playing) R.string.video_pause else R.string.video_play)
    fun action(requestCode: Int, command: Int, icon: Int, text: String) = android.app.RemoteAction(
        android.graphics.drawable.Icon.createWithResource(this, icon),
        text,
        text,
        android.app.PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(DetailActivity.ACTION_PICTURE_IN_PICTURE_TOGGLE).setPackage(packageName)
                .putExtra(DetailActivity.EXTRA_PICTURE_IN_PICTURE_COMMAND, command),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
    )
    val toggle = action(0, DetailActivity.PIP_COMMAND_TOGGLE, if (playing) R.drawable.ic_pause else R.drawable.ic_play, label)
    // Android lays the buttons out in a row: previous, play/pause in the center, next.
    val actions = if (mediaQueue.size > 1) {
        listOf(
            action(1, DetailActivity.PIP_COMMAND_PREVIOUS, R.drawable.ic_skip_previous, getString(R.string.viewer_previous_media)),
            toggle,
            action(2, DetailActivity.PIP_COMMAND_NEXT, R.drawable.ic_skip_next, getString(R.string.viewer_next_media))
        )
    } else listOf(toggle)
    return android.app.PictureInPictureParams.Builder()
        .setAspectRatio(android.util.Rational(width, height))
        .setActions(actions)
        // Where the video is on screen: Android animates from it instead of showing the app icon.
        .apply { videoSourceRect()?.let { setSourceRectHint(it) } }
        .build()
}

internal fun DetailActivity.updatePictureInPictureActions() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPictureInPictureModeCompat()) return
    val playing = videoActiveForPictureInPicture()
    val (shownWidth, shownHeight) = videoDisplaySize()
    val ratio = DetailPictureInPictureRules.aspectRatio(shownWidth, shownHeight)
    if (pictureInPictureShownPlaying == playing && pictureInPictureShownRatio == ratio) return
    pictureInPictureShownPlaying = playing
    runCatching { setPictureInPictureParams(pictureInPictureParams()) }
}

/**
 * Shape of the video exactly as the viewer shows it, so a vertical video gets a vertical thumbnail.
 * The player frame already applies rotation and pixel shape; the decoder size is the fallback.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun DetailActivity.videoDisplaySize(): Pair<Int, Int> {
    val frame = activePage?.findViewWithTag<PlayerView>("detail_video_player")
        ?.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
    if (frame != null && frame.width > 0 && frame.height > 0) return frame.width to frame.height
    val size = playbackController.player()?.videoSize ?: return 0 to 0
    @Suppress("DEPRECATION")
    return DetailPictureInPictureRules.orientedSize(
        size.width, size.height, size.pixelWidthHeightRatio, size.unappliedRotationDegrees
    )
}

/** The video frame in window coordinates, or null when it is not on screen. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun DetailActivity.videoSourceRect(): android.graphics.Rect? {
    val frame = activePage?.findViewWithTag<PlayerView>("detail_video_player")
        ?.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame) ?: return null
    val rect = android.graphics.Rect()
    return rect.takeIf { frame.getGlobalVisibleRect(it) && !it.isEmpty }
}

internal fun DetailActivity.registerPictureInPictureControls() {
    if (pictureInPictureReceiver != null) return
    val receiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action != DetailActivity.ACTION_PICTURE_IN_PICTURE_TOGGLE) return
            when (intent.getIntExtra(DetailActivity.EXTRA_PICTURE_IN_PICTURE_COMMAND, DetailActivity.PIP_COMMAND_TOGGLE)) {
                DetailActivity.PIP_COMMAND_PREVIOUS -> switchFromPictureInPicture(-1)
                DetailActivity.PIP_COMMAND_NEXT -> switchFromPictureInPicture(1)
                else -> {
                    playbackController.setPlaying(!videoActiveForPictureInPicture())
                    updatePlayPauseButton()
                    updatePictureInPictureActions()
                }
            }
        }
    }
    androidx.core.content.ContextCompat.registerReceiver(
        this,
        receiver,
        android.content.IntentFilter(DetailActivity.ACTION_PICTURE_IN_PICTURE_TOGGLE),
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
    )
    pictureInPictureReceiver = receiver
}

internal fun DetailActivity.switchFromPictureInPicture(direction: Int) {
    switchItem(direction, true)
    // The new media has its own shape and play state: resend the thumbnail buttons once it settles.
    handler.postDelayed({
        pictureInPictureShownPlaying = null
        updatePictureInPictureActions()
    }, DetailActivity.PIP_SWITCH_REFRESH_MS)
}

internal fun DetailActivity.unregisterPictureInPictureControls() {
    pictureInPictureReceiver?.let { runCatching { unregisterReceiver(it) } }
    pictureInPictureReceiver = null
}

internal fun DetailActivity.hideViewerChromeForPictureInPicture() {
    // Already hidden right before entering: keep the visibility saved at that moment.
    if (viewsHiddenForPictureInPicture.isNotEmpty()) return
    val chrome = pictureInPictureChrome()
    for (view in chrome) {
        viewsHiddenForPictureInPicture += view to view.visibility
        view.visibility = View.GONE
    }
}

internal fun DetailActivity.restoreViewerChromeAfterPictureInPicture() {
    cancelPendingSingleTap()
    // Back from the thumbnail the controls are always shown, so a tap delivered while
    // expanding cannot leave the viewer without its bars.
    hudVisible = true
    for ((view, visibility) in viewsHiddenForPictureInPicture) {
        view.animate().cancel()
        view.alpha = 1f
        view.visibility = if (view === topBar || view === bottomBar) View.VISIBLE else visibility
    }
    viewsHiddenForPictureInPicture.clear()
    updatePlayPauseButton()
}
