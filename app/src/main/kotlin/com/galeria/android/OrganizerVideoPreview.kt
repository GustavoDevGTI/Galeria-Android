package com.galeria.android

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.TextureView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.SeekBar
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.MediaItem as PlayerMediaItem

/** A transparent in-place player: just play/pause and a seek bar. */
@OptIn(UnstableApi::class)
internal class OrganizerVideoPreview(context: Context, item: MediaItem) : FrameLayout(context) {
    private val texture = TextureView(context)
    private val play = ImageButton(context)
    private val seek = SeekBar(context)
    private var aspect = 1f
    private var seeking = false
    private var released = false
    private val player = ExoPlayer.Builder(context.applicationContext).build()
    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            if (!seeking && player.duration > 0) seek.progress = (player.currentPosition * 1000 / player.duration).toInt()
            postDelayed(this, 150)
        }
    }
    init {
        tag = "organizer_video_preview"
        addView(texture, LayoutParams(-1, -1, Gravity.CENTER))
        texture.visibility = INVISIBLE
        play.apply {
            tag = "organizer_video_play_pause"; setBackgroundColor(Color.TRANSPARENT)
            setColorFilter(Color.WHITE); setImageResource(R.drawable.ic_pause)
            contentDescription = "Pausar vídeo"
            setOnClickListener {
                if (player.isPlaying) player.pause() else {
                    if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                    player.play()
                }
            }
        }
        addView(play, LayoutParams(Ui.dp(context, 64), Ui.dp(context, 64), Gravity.CENTER))
        seek.apply {
            tag = "organizer_video_seek"; max = 1000
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    if (player.duration > 0) player.seekTo(player.duration * bar.progress / 1000)
                    seeking = false
                }
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) = Unit
            })
        }
        addView(seek, LayoutParams(-1, Ui.dp(context, 48), Gravity.BOTTOM))
        player.setVideoTextureView(texture)
        player.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() { texture.visibility = VISIBLE }
            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.height > 0) aspect = size.width * size.pixelWidthHeightRatio / size.height
                fitVideo()
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                play.contentDescription = if (playing) "Pausar vídeo" else "Reproduzir vídeo"
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Ui.toast(context, "Não foi possível reproduzir este vídeo.")
            }
        })
        player.setMediaItem(PlayerMediaItem.fromUri(item.uri)); player.prepare(); player.play()
        post(tick)
    }
    private fun fitVideo() {
        if (width <= 0 || height <= 0) return
        val w = kotlin.math.min(width.toFloat(), height * aspect).toInt()
        val h = (w / aspect).toInt()
        texture.layoutParams = LayoutParams(w, h, Gravity.CENTER)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); fitVideo() }
    override fun onDetachedFromWindow() { release(); super.onDetachedFromWindow() }
    fun release() { if (!released) { released = true; removeCallbacks(tick); player.release() } }
}
