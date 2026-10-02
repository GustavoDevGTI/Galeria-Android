package com.galeria.android

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters

/** Adapter owned by the player owner, not by a timeline/control View. */
@OptIn(UnstableApi::class)
internal class ExoPlaybackCommands {
    private val handler = Handler(Looper.getMainLooper())
    private val authority = PlaybackCommandController(object : PlaybackCommandController.Scheduler {
        override fun post(work: Runnable, delayMs: Long) { handler.postDelayed(work, delayMs) }
        override fun cancel(work: Runnable) { handler.removeCallbacks(work) }
    })
    var channel: PlaybackCommandController.Channel? = null
        private set
    fun bind(player: ExoPlayer): PlaybackCommandController.Channel = authority.bind(object : PlaybackCommandController.Port {
        override val position get() = player.currentPosition
        override val duration get() = player.duration
        override val isPlaying get() = player.isPlaying
        override val playWhenReady get() = player.playWhenReady
        override val ended get() = player.playbackState == Player.STATE_ENDED
        override fun play() { player.play() }
        override fun pause() { player.pause() }
        override fun seek(position: Long) { player.seekTo(position) }
        override fun exactSeek(): () -> Unit {
            val original = player.seekParameters
            player.setSeekParameters(SeekParameters.EXACT)
            return { player.setSeekParameters(original) }
        }
    }).also { channel = it }
    fun suspend() { authority.suspend() }
    fun resume(restorePlayback: Boolean = false) { authority.resume(restorePlayback) }
    fun detach() { authority.detach(); channel = null }
    fun close() { authority.close(); channel = null }
}
