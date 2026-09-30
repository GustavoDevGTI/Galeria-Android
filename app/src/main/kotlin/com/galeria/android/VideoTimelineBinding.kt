package com.galeria.android

import android.net.Uri
import android.os.Handler
import android.os.Looper
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
    var onPosition: ((Long, Long) -> Unit)? = null
    var onScrubbing: ((Boolean) -> Unit)? = null
    private val seek = Runnable {
        seekScheduled = false
        pendingPosition?.let { player?.seekTo(it) }
        pendingPosition = null
    }
    private val tick = object : Runnable {
        override fun run() {
            val current = player ?: return
            val duration = current.duration.coerceAtLeast(0L)
            view.update(current.currentPosition, duration)
            onPosition?.invoke(if (view.isScrubbing) view.positionMs else current.currentPosition, duration)
            handler.postDelayed(this, 100L)
        }
    }

    init {
        view.onScrubStart = {
            player?.let {
                resume = it.playWhenReady
                beforeSeek = it.currentPosition
                originalSeek = it.seekParameters
                it.pause()
                it.setSeekParameters(SeekParameters.EXACT)
                onScrubbing?.invoke(true)
            }
        }
        view.onScrubMove = { position ->
            pendingPosition = position
            if (!seekScheduled) { seekScheduled = true; handler.postDelayed(seek, 60L) }
            onPosition?.invoke(position, view.durationMs)
        }
        view.onScrubStop = { position, cancelled ->
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
    }

    fun bind(player: ExoPlayer, uri: Uri, revision: String = "") {
        unbind()
        this.player = player
        view.setSource(uri, revision)
        handler.post(tick)
    }

    fun suspend() {
        resume = false
        view.cancelGesture()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(seek)
        seekScheduled = false
    }

    fun resumeUpdates() { handler.removeCallbacks(tick); if (player != null) handler.post(tick) }

    fun unbind() {
        suspend()
        player = null
        view.setSource(null)
    }
}
