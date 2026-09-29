package com.galeria.android

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

class MotionPhotoActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.applySystemBars(this)
        val source = intent.getStringExtra(EXTRA_URI)?.let(Uri::parse)
        val offset = intent.getLongExtra(EXTRA_OFFSET, -1L)
        val length = intent.getLongExtra(EXTRA_LENGTH, -1L)
        if (source == null || offset < 0L || length <= 0L) {
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val playerView = PlayerView(this).apply {
            tag = "motion_photo_player"
            useController = true
            setBackgroundColor(Color.BLACK)
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val progress = ProgressBar(this)
        root.addView(progress, FrameLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42), Gravity.CENTER))
        val back = TextView(this).apply {
            text = getString(R.string.album_back)
            textSize = 17f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER_VERTICAL
            Ui.applySystemBarPadding(this, 16, 14, 0, 0)
            isClickable = true
            setOnClickListener { finish() }
        }
        root.addView(back, FrameLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 96), Gravity.TOP or Gravity.START))
        setContentView(root)

        executor.execute {
            val media = runCatching {
                MotionPhotoSupport.cachedClip(applicationContext, source, MotionPhotoClip(offset, length))
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                media.onSuccess { file ->
                    val created = ExoPlayer.Builder(this).build()
                    player = created
                    playerView.player = created
                    created.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_READY || state == Player.STATE_ENDED) {
                                progress.visibility = android.view.View.GONE
                            }
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            progress.visibility = android.view.View.GONE
                            Ui.toast(this@MotionPhotoActivity, getString(R.string.motion_photo_error))
                        }
                    })
                    created.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    created.prepare()
                    created.play()
                }.onFailure {
                    progress.visibility = android.view.View.GONE
                    Ui.toast(this, getString(R.string.motion_photo_error))
                    finish()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        executor.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URI = "motion_uri"
        const val EXTRA_OFFSET = "motion_offset"
        const val EXTRA_LENGTH = "motion_length"
    }
}
