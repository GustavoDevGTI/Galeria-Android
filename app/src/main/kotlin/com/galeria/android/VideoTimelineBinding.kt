package com.galeria.android

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.SeekBar
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

/** Coalesces seeks and preserves playback state independently of thumbnail availability. */
@OptIn(UnstableApi::class)
internal class VideoTimelineBinding(private val view: VideoTimelineView) {
    private val handler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var resume = false
    private var beforeSeek = 0L
    private var originalSeek = SeekParameters.DEFAULT
    private var pendingPosition: Long? = null
    private var seekScheduled = false
    private var progressBar: SeekBar? = null
    private var progressTracking = false
    private var progressPosition = 0L
    var onPosition: ((Long, Long) -> Unit)? = null
    var onScrubbing: ((Boolean) -> Unit)? = null
    val scrubPositionMs: Long?
        get() = if (view.isScrubbing) view.positionMs else if (progressTracking) progressPosition else null
    private val seek = Runnable {
        seekScheduled = false
        pendingPosition?.let { player?.seekTo(it) }
        pendingPosition = null
    }
    private val tick = object : Runnable {
        override fun run() {
            val current = player ?: return
            val duration = current.duration.coerceAtLeast(0L)
            view.update(if (progressTracking) progressPosition else current.currentPosition, duration)
            val position = if (view.isScrubbing) view.positionMs else if (progressTracking) progressPosition else current.currentPosition
            if (!progressTracking) {
                progressBar?.apply {
                    isEnabled = duration > 0L
                    progress = if (duration > 0L) (position.toDouble() / duration * max).toInt().coerceIn(0, max) else 0
                }
            }
            onPosition?.invoke(position, duration)
            handler.postDelayed(this, 100L)
        }
    }

    init {
        view.onScrubStart = { beginSeek() }
        view.onScrubMove = { moveSeek(it) }
        view.onScrubStop = { position, cancelled -> finishSeek(position, cancelled) }
    }

    /** The simple playback bar remains available even when the filmstrip is closed. */
    fun attachProgressBar(bar: SeekBar) {
        progressBar = bar
        bar.max = 100_000
        bar.isEnabled = false
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                progressTracking = true
                progressPosition = player?.currentPosition ?: 0L
                beginSeek()
            }

            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val duration = player?.duration?.coerceAtLeast(0L) ?: return
                if (duration <= 0L) return
                progressPosition = VideoTimelineRules.progressPosition(progress, seekBar.max, duration)
                if (!progressTracking) beginSeek() // Keyboard and accessibility may skip touch callbacks.
                view.update(progressPosition, duration)
                moveSeek(progressPosition)
                if (!progressTracking) finishSeek(progressPosition, false)
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                if (!progressTracking) return
                progressTracking = false
                finishSeek(progressPosition, false)
            }
        })
    }

    private fun beginSeek() {
        player?.let {
            resume = it.playWhenReady
            beforeSeek = it.currentPosition
            originalSeek = it.seekParameters
            it.pause()
            it.setSeekParameters(SeekParameters.EXACT)
            onScrubbing?.invoke(true)
        }
    }

    private fun moveSeek(position: Long) {
        pendingPosition = position
        if (!seekScheduled) { seekScheduled = true; handler.postDelayed(seek, 60L) }
        onPosition?.invoke(position, player?.duration?.coerceAtLeast(0L) ?: 0L)
    }

    private fun finishSeek(position: Long, cancelled: Boolean) {
        handler.removeCallbacks(seek)
        seekScheduled = false
        pendingPosition = null
        player?.let {
            it.seekTo(if (cancelled) beforeSeek else position.coerceAtMost((it.duration - 1L).coerceAtLeast(0L)))
            it.setSeekParameters(originalSeek)
            if (resume) it.play()
        }
        onScrubbing?.invoke(false)
    }

    fun bind(player: ExoPlayer, uri: Uri, revision: String = "") {
        unbind()
        this.player = player
        view.setSource(uri, revision)
        view.setPreparationEnabled(true)
        handler.post(tick)
    }

    fun suspend() {
        resume = false
        view.setPreparationEnabled(false)
        if (progressTracking) { progressTracking = false; finishSeek(progressPosition, true) }
        view.cancelGesture()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(seek)
        seekScheduled = false
    }

    fun resumeUpdates() {
        handler.removeCallbacks(tick)
        if (player != null) { view.setPreparationEnabled(true); handler.post(tick) }
    }

    fun unbind() {
        suspend()
        player = null
        view.setSource(null)
    }
}
