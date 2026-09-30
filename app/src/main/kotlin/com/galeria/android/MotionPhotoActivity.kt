package com.galeria.android

import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.ImageButton
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.util.concurrent.Executors

class MotionPhotoActivity : ComponentActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private var player: ExoPlayer? = null
    private lateinit var timelineBinding: VideoTimelineBinding

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
            useController = false
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
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x88000000.toInt())
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left + Ui.dp(context, 16), Ui.dp(context, 4), bars.right + Ui.dp(context, 16), bars.bottom + Ui.dp(context, 8))
                insets
            }
        }
        val times = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER }
        val play = ImageButton(this).apply {
            setImageResource(R.drawable.ic_pause)
            imageTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            background = Ui.actionFeedback(this@MotionPhotoActivity, Color.WHITE)
            contentDescription = getString(R.string.video_pause)
            setOnClickListener {
                player?.let { current ->
                    if (current.isPlaying) current.pause() else {
                        if (current.playbackState == Player.STATE_ENDED) current.seekTo(0L)
                        current.play()
                    }
                }
            }
        }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(play, LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)))
        row.addView(times, LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1f))
        controls.addView(row)
        val timeline = VideoTimelineView(this)
        controls.addView(timeline, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 58)))
        timelineBinding = VideoTimelineBinding(timeline).apply {
            onPosition = { position, duration ->
                times.text = "${videoTime(position)} / ${videoTime(duration)}"
                val playing = player?.isPlaying == true
                play.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                play.contentDescription = getString(if (playing) R.string.video_pause else R.string.video_play)
            }
        }
        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
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
                    timelineBinding.bind(created, Uri.fromFile(file), "${file.length()}|${file.lastModified()}")
                    created.prepare()
                    if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                        created.play()
                    } else {
                        timelineBinding.suspend()
                    }
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
        if (::timelineBinding.isInitialized) timelineBinding.suspend()
        player?.pause()
    }

    override fun onStart() {
        super.onStart()
        if (::timelineBinding.isInitialized) timelineBinding.resumeUpdates()
    }

    private fun videoTime(ms: Long): String = "%02d:%02d".format(ms.coerceAtLeast(0L) / 60_000L, ms.coerceAtLeast(0L) / 1000L % 60L)

    override fun onDestroy() {
        if (::timelineBinding.isInitialized) timelineBinding.unbind()
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
