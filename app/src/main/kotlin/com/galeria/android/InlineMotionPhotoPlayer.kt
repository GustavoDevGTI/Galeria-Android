package com.galeria.android

import android.app.Activity
import android.graphics.Matrix
import android.graphics.RectF
import android.net.Uri
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.concurrent.Executor

/** Where the clip is drawn: inside the photo's on-screen rectangle, keeping the clip's proportions. */
internal object MotionPhotoInlineRules {
    data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /**
     * The clip normally has the photo's proportions and covers it exactly; when it does not (a 16:9
     * clip on a 4:3 photo), it is centred inside the photo and the photo shows around it.
     */
    fun videoBox(photo: Box, videoWidth: Int, videoHeight: Int): Box {
        if (videoWidth <= 0 || videoHeight <= 0 || photo.width <= 0f || photo.height <= 0f) return photo
        val scale = minOf(photo.width / videoWidth, photo.height / videoHeight)
        val width = videoWidth * scale
        val height = videoHeight * scale
        val left = photo.left + (photo.width - width) / 2f
        val top = photo.top + (photo.height - height) / 2f
        return Box(left, top, left + width, top + height)
    }
}

/**
 * Plays a Motion Photo's clip on top of the photo itself, at the same place and zoom, and fades
 * back to the photo when it ends. The surface takes no touches, so pinch, pan, swipe and taps keep
 * reaching the photo underneath; the clip follows any zoom made while it plays.
 */
internal class InlineMotionPhotoPlayer(
    private val activity: Activity,
    private val executor: Executor,
    private val muted: () -> Boolean,
    private val onPlayingChanged: (Boolean) -> Unit,
    private val onFailed: () -> Unit,
    private val onReady: (ExoPlayer, Uri, PlaybackCommandController.Channel) -> Unit
) {
    private var session: MotionPhotoPlaybackSession<File, ExoPlayer>? = null
    private var commands: ExoPlaybackCommands? = null
    val player: ExoPlayer? get() = session?.player
    val commandChannel: PlaybackCommandController.Channel? get() = commands?.channel
    private var surface: TextureView? = null
    private var photo: ImageView? = null
    private var follow: ViewTreeObserver.OnPreDrawListener? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private val lastTransform = Matrix()

    val isPlaying: Boolean get() = session != null

    fun play(page: FrameLayout, image: ImageView, uri: Uri, clip: MotionPhotoClip) {
        stop(animated = false)
        val view = TextureView(activity).apply {
            tag = SURFACE_TAG
            alpha = 0f
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        page.addView(view, page.indexOfChild(image) + 1,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        surface = view
        photo = image
        videoWidth = 0
        videoHeight = 0
        lastTransform.reset()
        val listener = ViewTreeObserver.OnPreDrawListener { followPhoto(); true }
        follow = listener
        view.viewTreeObserver.addOnPreDrawListener(listener)

        lateinit var created: MotionPhotoPlaybackSession<File, ExoPlayer>
        // Each fading session keeps its own command authority until it releases its player.
        val ownedCommands = ExoPlaybackCommands()
        commands = ownedCommands
        created = MotionPhotoPlaybackSession(
            hostActive = { session === created && !activity.isFinishing && !activity.isDestroyed },
            create = { ExoPlayer.Builder(activity).build() },
            bindAndPrepare = { player, file ->
                val channel = ownedCommands.bind(player)
                player.setVideoTextureView(view)
                player.volume = if (muted()) 0f else 1f
                player.repeatMode = Player.REPEAT_MODE_OFF
                player.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(size: VideoSize) {
                        val rotated = size.unappliedRotationDegrees % 180 != 0
                        videoWidth = if (rotated) size.height else size.width
                        videoHeight = if (rotated) size.width else size.height
                        lastTransform.reset()
                        followPhoto()
                    }
                    override fun onRenderedFirstFrame() {
                        if (session !== created) return
                        view.animate().cancel()
                        view.animate().alpha(1f).setDuration(Motion.POPOVER_MS).setInterpolator(Motion.easeOut).start()
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED && session === created && player.playWhenReady &&
                            !channel.repeatSelection()) stop(animated = true)
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        if (session !== created) return
                        stop(animated = false)
                        onFailed()
                    }
                })
                player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                player.prepare()
                onReady(player, Uri.fromFile(file), channel)
            },
            play = { ownedCommands.channel?.play() },
            pause = { ownedCommands.channel?.pause() },
            release = { it.release() },
            resumeUpdates = { ownedCommands.resume() },
            suspendUpdates = { ownedCommands.suspend() },
            unbind = { ownedCommands.close() },
            failed = {
                if (session === created) {
                    stop(animated = false)
                    onFailed()
                }
            }
        )
        session = created
        onPlayingChanged(true)
        val context = activity.applicationContext
        created.load { complete ->
            executor.execute {
                val media = runCatching { MotionPhotoSupport.cachedClip(context, uri, clip) }
                activity.runOnUiThread { complete(media) }
            }
        }
        created.start()
    }

    /** Ends playback. Animated, the clip fades out over the photo; otherwise it disappears at once. */
    fun stop(animated: Boolean) {
        val owned = session ?: return
        session = null
        commands = null
        val view = surface
        surface = null
        photo = null
        follow?.let { listener -> view?.viewTreeObserver?.takeIf { it.isAlive }?.removeOnPreDrawListener(listener) }
        follow = null
        onPlayingChanged(false)
        val finish = {
            owned.close()
            (view?.parent as? ViewGroup)?.removeView(view)
            Unit
        }
        if (animated && view != null && view.isAttachedToWindow && view.alpha > 0f) {
            owned.stop()
            view.animate().cancel()
            view.animate().alpha(0f).setDuration(FADE_OUT_MS).setInterpolator(Motion.easeOut)
                .withEndAction(finish).start()
        } else {
            view?.animate()?.cancel()
            finish()
        }
    }

    /** Keeps the clip on the photo's on-screen rectangle, including zoom and pan made while it plays. */
    private fun followPhoto() {
        val view = surface ?: return
        val image = photo ?: return
        val drawable = image.drawable ?: return
        if (view.width == 0 || view.height == 0) return
        val bounds = RectF(0f, 0f, drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        image.imageMatrix.mapRect(bounds)
        bounds.offset(image.left + image.paddingLeft.toFloat() - view.left, image.top + image.paddingTop.toFloat() - view.top)
        val box = MotionPhotoInlineRules.videoBox(
            MotionPhotoInlineRules.Box(bounds.left, bounds.top, bounds.right, bounds.bottom), videoWidth, videoHeight)
        val transform = Matrix().apply {
            setRectToRect(RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                RectF(box.left, box.top, box.right, box.bottom), Matrix.ScaleToFit.FILL)
        }
        if (transform != lastTransform) {
            lastTransform.set(transform)
            view.setTransform(transform)
            view.invalidate()
        }
    }

    companion object {
        const val SURFACE_TAG = "motion_photo_inline"
        private const val FADE_OUT_MS = 260L
    }
}
